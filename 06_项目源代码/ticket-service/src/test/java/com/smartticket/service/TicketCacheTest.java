package com.smartticket.service;

import com.smartticket.dto.TicketAssignDTO;
import com.smartticket.dto.TicketCloseDTO;
import com.smartticket.dto.TicketUpdateDTO;
import com.smartticket.exception.BusinessException;
import com.smartticket.vo.TicketVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第9天验收：工单详情缓存（Cache-Aside 模式）。
 *
 * <p>直接调用 Service 层而非走 HTTP：本测试的关注点是缓存行为本身，
 * 不需要经过参数校验与返回体包装。</p>
 *
 * <p><b>关键证明手法</b>：「第二次查询走 Redis」这件事无法靠日志断言，
 * 因此在 {@link #secondQueryIsServedFromCache} 中用<b>绕过 Service 直接改数据库</b>的方式：
 * 若接口仍返回旧值，说明这次读取根本没碰数据库；再删掉缓存后立刻能读到新值，构成双向反证。</p>
 *
 * <p>测试数据用独立的 creatorId 隔离，并在 {@code @AfterEach} 中同时清理
 * 数据库行（物理删除）与 Redis 缓存 key —— 只删数据库会留下指向"已不存在工单"的缓存垃圾。</p>
 */
@SpringBootTest
class TicketCacheTest {

    /** 测试专用创建人ID，与 TicketApiTest 的 999_999 区分开，避免互相清理数据 */
    private static final long TEST_CREATOR_ID = 999_998L;

    /** 测试专用操作人ID */
    private static final long TEST_OPERATOR_ID = 888_888L;

    /** 测试专用处理人ID */
    private static final long TEST_ASSIGNEE_ID = 777_777L;

    /** 不存在的工单ID */
    private static final long UNKNOWN_TICKET_ID = 999_999_999L;

    /** 与 TicketServiceImpl.CACHE_KEY_DETAIL_PREFIX 保持一致 */
    private static final String CACHE_KEY_PREFIX = "ticket:detail:";

    @Autowired
    private TicketService ticketService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 记录本用例涉及的工单 id，供 @AfterEach 精确清理缓存 key */
    private final List<Long> usedTicketIds = new ArrayList<>();

    @AfterEach
    void cleanUpTestDataAndCache() {
        usedTicketIds.forEach(id -> stringRedisTemplate.delete(CACHE_KEY_PREFIX + id));
        usedTicketIds.clear();

        jdbcTemplate.update(
                "DELETE FROM ticket_log WHERE ticket_id IN (SELECT id FROM ticket WHERE creator_id = ?)",
                TEST_CREATOR_ID);
        jdbcTemplate.update("DELETE FROM ticket WHERE creator_id = ?", TEST_CREATOR_ID);
    }

    @Test
    @DisplayName("第一次查询：回源数据库并把结果回填缓存，TTL 为 10 分钟")
    void firstQueryLoadsFromDatabaseAndFillsCache() {
        long id = insertTicketDirectly("缓存测试-首次查询", "PENDING_ASSIGN");
        String cacheKey = CACHE_KEY_PREFIX + id;

        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(cacheKey)), "查询前不应存在缓存");

        TicketVO ticketVO = ticketService.getById(id);
        assertEquals("缓存测试-首次查询", ticketVO.getTitle());

        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(cacheKey)), "首次查询后应回填缓存");

        Long ttlSeconds = stringRedisTemplate.getExpire(cacheKey);
        assertNotNull(ttlSeconds, "缓存 key 应有 TTL");
        assertTrue(ttlSeconds > 0 && ttlSeconds <= Duration.ofMinutes(10).toSeconds(),
                "TTL 应在 (0, 600] 秒之间（守护「忘设过期时间」），实际=" + ttlSeconds);
    }

    @Test
    @DisplayName("第二次查询走缓存：绕过数据库改动仍返回旧值，缓存删除后立刻返回新值")
    void secondQueryIsServedFromCache() {
        long id = insertTicketDirectly("缓存测试-旧标题", "PENDING_ASSIGN");

        // 第一次查询建立缓存
        assertEquals("缓存测试-旧标题", ticketService.getById(id).getTitle());

        // 绕过 Service 直接改库，模拟"缓存存续期间数据被改动"
        jdbcTemplate.update("UPDATE ticket SET title = ? WHERE id = ?", "缓存测试-新标题", id);

        // 若命中缓存，返回的仍应是旧标题 —— 这是"没查数据库"的强证据
        assertEquals("缓存测试-旧标题", ticketService.getById(id).getTitle(),
                "缓存命中时应返回旧值；返回新值说明这次查询又走了数据库");

        // 反证：清掉缓存后再查，必须能读到库里的新值
        stringRedisTemplate.delete(CACHE_KEY_PREFIX + id);
        assertEquals("缓存测试-新标题", ticketService.getById(id).getTitle(),
                "缓存失效后应回源拿到最新数据");
    }

    @Test
    @DisplayName("更新工单后缓存被删除，再次查询拿到新值")
    void updateEvictsCache() {
        long id = insertTicketDirectly("缓存测试-待更新", "PENDING_ASSIGN");
        ticketService.getById(id);
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(CACHE_KEY_PREFIX + id)),
                "前置条件：缓存应已建立");

        TicketUpdateDTO dto = new TicketUpdateDTO();
        dto.setTitle("缓存测试-已更新");
        dto.setPriority("HIGH");
        dto.setOperatorId(TEST_OPERATOR_ID);
        ticketService.update(id, dto);

        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(CACHE_KEY_PREFIX + id)),
                "更新后缓存应被删除");
        assertEquals("缓存测试-已更新", ticketService.getById(id).getTitle(), "再次查询应拿到新值");
    }

    @Test
    @DisplayName("关闭工单后缓存被删除")
    void closeEvictsCache() {
        long id = insertTicketDirectly("缓存测试-待关闭", "PENDING_ASSIGN");
        ticketService.getById(id);

        TicketCloseDTO dto = new TicketCloseDTO();
        dto.setOperatorId(TEST_OPERATOR_ID);
        ticketService.close(id, dto);

        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(CACHE_KEY_PREFIX + id)),
                "关闭后缓存应被删除");
    }

    @Test
    @DisplayName("指派工单后缓存被删除（指导清单未列出，但它会改 status 与 assignee_id）")
    void assignEvictsCache() {
        long id = insertTicketDirectly("缓存测试-待指派", "PENDING_ASSIGN");
        ticketService.getById(id);

        TicketAssignDTO dto = new TicketAssignDTO();
        dto.setAssigneeId(TEST_ASSIGNEE_ID);
        dto.setOperatorId(TEST_OPERATOR_ID);
        ticketService.assign(id, dto);

        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(CACHE_KEY_PREFIX + id)),
                "指派后缓存应被删除，否则会返回旧处理人与旧状态");
        assertEquals(TEST_ASSIGNEE_ID, ticketService.getById(id).getAssigneeId().longValue(),
                "再次查询应拿到新的处理人");
    }

    @Test
    @DisplayName("查询不存在的工单抛 10003，且不产生缓存（防穿透）")
    void missingTicketIsNotCached() {
        String cacheKey = CACHE_KEY_PREFIX + UNKNOWN_TICKET_ID;

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.getById(UNKNOWN_TICKET_ID));
        assertEquals(10003, exception.getErrorCode().getCode(), "不存在的工单应返回 10003");

        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(cacheKey)),
                "查询失败不应写入缓存（否则不存在的 id 会长期占用内存）");
    }

    @Test
    @DisplayName("缓存内容是可直接阅读的 JSON，且不含内部字段 deleted")
    void cacheValueIsReadableJson() {
        long id = insertTicketDirectly("缓存测试-JSON可读", "PENDING_ASSIGN");
        ticketService.getById(id);

        String json = stringRedisTemplate.opsForValue().get(CACHE_KEY_PREFIX + id);
        assertNotNull(json, "缓存内容不应为空");
        assertTrue(json.contains("\"ticketNo\""), "缓存应为字段名可读的 JSON，实际=" + json);
        assertTrue(json.contains("缓存测试-JSON可读"), "缓存应包含工单标题");
        assertFalse(json.contains("deleted"), "缓存不应包含内部字段 deleted，实际=" + json);
    }

    // ---------------------------------------------------------------- 辅助方法

    /** 直插一条工单（返回 id），并登记以便 @AfterEach 清理其缓存 key */
    private long insertTicketDirectly(String title, String status) {
        String ticketNo = "ST-CACHE-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO ticket "
                        + "(ticket_no, title, description, category, priority, status, creator_id, tags) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ticketNo, title, "缓存测试数据", "其他", "LOW", status, TEST_CREATOR_ID, null);

        Long id = jdbcTemplate.queryForObject("SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
        long ticketId = id == null ? -1L : id;
        usedTicketIds.add(ticketId);
        return ticketId;
    }

}
