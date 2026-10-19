package com.smartticket.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartticket.dto.TicketAssignDTO;
import com.smartticket.dto.TicketCloseDTO;
import com.smartticket.dto.TicketCreateDTO;
import com.smartticket.dto.TicketResolveDTO;
import com.smartticket.dto.TicketUpdateDTO;
import com.smartticket.entity.Ticket;
import com.smartticket.entity.TicketLog;
import com.smartticket.exception.BusinessException;
import com.smartticket.mapper.TicketLogMapper;
import com.smartticket.mapper.TicketMapper;
import com.smartticket.vo.TicketVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第12天验收：{@code TicketServiceImpl} 的**纯单元测试**。
 *
 * <p>与 {@code TicketApiTest} / {@code TicketCacheTest} 的分工：那些是集成测试（走真实 MySQL 与 Redis），
 * 本类用 Mockito 替掉两个 Mapper 与 {@code StringRedisTemplate}，<b>不启动 Spring、不依赖 Docker</b>，
 * 可在容器未运行时秒级验证业务分支。</p>
 *
 * <p>能覆盖集成测试难以构造的分支，典型如「编号连续撞唯一键 3 次致失败」与
 * 「缓存中存了非法 JSON」。后者在真实环境里需要手工往 Redis 塞脏数据才能复现。</p>
 *
 * <p>{@code ObjectMapper} 使用真实实例（而非 mock）：它无副作用，且缓存分支的行为依赖真实序列化结果；
 * 必须注册 {@code JavaTimeModule}，否则 {@code TicketVO} 中的 {@code LocalDateTime} 会序列化失败。</p>
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceImplTest {

    private static final Long TICKET_ID = 1L;

    private static final Long OPERATOR_ID = 100L;

    private static final Long ASSIGNEE_ID = 200L;

    private static final String CACHE_KEY = "ticket:detail:" + TICKET_ID;

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private TicketLogMapper ticketLogMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TicketServiceImpl ticketService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 纯单元测试没有 MyBatis 上下文，而 Wrappers.lambdaUpdate(...) 需要实体的 TableInfo 缓存
        // 才能把方法引用解析成列名；不手动初始化会抛
        // MybatisPlusException: can not find lambda cache for this entity
        if (TableInfoHelper.getTableInfo(Ticket.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Ticket.class);
        }

        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        org.mockito.Mockito.lenient().when(ticketMapper.update(isNull(), ArgumentMatchers.<Wrapper<Ticket>>any())).thenReturn(1);
        ticketService = new TicketServiceImpl(ticketMapper, ticketLogMapper, stringRedisTemplate, objectMapper);
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("创建工单：编号由服务端生成、状态固定为待分配、写入 CREATE 日志、返回回读结果")
    void createGeneratesTicketNoAndWritesCreateLog() {
        stubInsertAssigningId();
        Ticket saved = ticket(TICKET_ID, "PENDING_ASSIGN");
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(saved);

        TicketVO result = ticketService.create(createDTO());

        assertNotNull(result);
        assertEquals(TICKET_ID, result.getId());
        assertEquals("PENDING_ASSIGN", result.getStatus(), "创建时状态必须固定为待分配");
        // 编号格式：ST-yyyyMMdd-HHmmss-4位十六进制
        assertTrue(result.getTicketNo().matches("^ST-\\d{8}-\\d{6}-[0-9A-F]{4}$"),
                "工单编号格式不符：" + result.getTicketNo());
        verify(ticketLogMapper).insert(ArgumentMatchers.<TicketLog>any());
    }

    @Test
    @DisplayName("创建工单：未传优先级时显式写入 MEDIUM（而非依赖数据库默认值）")
    void createFallsBackToMediumPriority() {
        stubInsertAssigningId();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(TICKET_ID, "PENDING_ASSIGN"));

        TicketCreateDTO dto = createDTO();
        dto.setPriority(null);
        ticketService.create(dto);

        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).insert(captor.capture());
        assertEquals("MEDIUM", captor.getValue().getPriority(),
                "未传优先级时应显式写入 MEDIUM，保证出参与库内一致");
    }

    @Test
    @DisplayName("创建工单：编号撞唯一键后重新生成并重试，第二次成功")
    void createRetriesAfterDuplicateTicketNo() {
        when(ticketMapper.insert(ArgumentMatchers.<Ticket>any()))
                .thenThrow(new DuplicateKeyException("uk_ticket_no"))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, Ticket.class).setId(TICKET_ID);
                    return 1;
                });
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(TICKET_ID, "PENDING_ASSIGN"));

        TicketVO result = ticketService.create(createDTO());

        assertEquals(TICKET_ID, result.getId());
        verify(ticketMapper, times(2)).insert(ArgumentMatchers.<Ticket>any());
    }

    @Test
    @DisplayName("创建工单：编号连续冲突 3 次后放弃并返回 10004")
    void createThrowsWhenTicketNoConflictsThreeTimes() {
        when(ticketMapper.insert(ArgumentMatchers.<Ticket>any())).thenThrow(new DuplicateKeyException("uk_ticket_no"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.create(createDTO()));

        assertEquals(10004, exception.getErrorCode().getCode());
        // 重试必须封顶，否则会无限循环
        verify(ticketMapper, times(3)).insert(ArgumentMatchers.<Ticket>any());
    }

    // ---------------------------------------------------------------- getById

    @Test
    @DisplayName("查询详情：缓存命中时直接返回，完全不查数据库")
    void getByIdReturnsFromCacheWithoutQueryingDatabase() throws Exception {
        String cachedJson = objectMapper.writeValueAsString(TicketVO.from(ticket(TICKET_ID, "PROCESSING")));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(cachedJson);

        TicketVO result = ticketService.getById(TICKET_ID);

        assertEquals("PROCESSING", result.getStatus());
        verify(ticketMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("查询详情：缓存未命中时回源数据库并回填缓存")
    void getByIdLoadsFromDatabaseAndFillsCache() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(TICKET_ID, "PENDING_ASSIGN"));

        TicketVO result = ticketService.getById(TICKET_ID);

        assertEquals("PENDING_ASSIGN", result.getStatus());
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(CACHE_KEY), jsonCaptor.capture(), any());
        assertTrue(jsonCaptor.getValue().contains("ST-20260923-120000-ABCD"),
                "回填内容应是 VO 的 JSON，实际=" + jsonCaptor.getValue());
    }

    @Test
    @DisplayName("查询详情：缓存内容非法时清除坏 key 并回源（缓存故障不阻断查询）")
    void getByIdClearsBrokenCacheAndFallsBackToDatabase() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn("{ 这不是合法 JSON");
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(TICKET_ID, "PENDING_ASSIGN"));

        TicketVO result = ticketService.getById(TICKET_ID);

        assertEquals("PENDING_ASSIGN", result.getStatus());
        verify(stringRedisTemplate).delete(CACHE_KEY);
        verify(ticketMapper).selectById(TICKET_ID);
    }

    @Test
    @DisplayName("查询详情：工单不存在时返回 10003，且不写入缓存")
    void getByIdThrowsWhenTicketMissing() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.getById(TICKET_ID));

        assertEquals(10003, exception.getErrorCode().getCode());
        verify(valueOperations, never()).set(any(), any(), any());
    }

    // ------------------------------------------------------------------- page

    @Test
    @DisplayName("分页查询：状态值非法时返回 10002，且不执行分页查询")
    void pageThrowsOnUnknownStatus() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.page(1, 10, "NOT_A_STATUS"));

        assertEquals(10002, exception.getErrorCode().getCode());
        verify(ticketMapper, never()).selectPage(any(), any());
    }

    // ----------------------------------------------------------------- update

    @Test
    @DisplayName("更新工单：用 UpdateWrapper 显式 set 全部字段（支持置空），并清除详情缓存")
    void updateReplacesAllFieldsAndEvictsCache() {
        when(ticketMapper.selectById(TICKET_ID))
                .thenReturn(ticket(TICKET_ID, "PENDING_ASSIGN"))
                .thenReturn(ticket(TICKET_ID, "PENDING_ASSIGN"));

        TicketUpdateDTO dto = new TicketUpdateDTO();
        dto.setTitle("更新后的标题");
        dto.setPriority("HIGH");
        dto.setOperatorId(OPERATOR_ID);
        // description / category / tags 不传 = 清空

        ticketService.update(TICKET_ID, dto);

        ArgumentCaptor<Wrapper<Ticket>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(ticketMapper).update(isNull(), wrapperCaptor.capture());
        String sqlSet = ((AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue()).getSqlSet();
        // 关键：置空的字段也必须出现在 SET 子句里（updateById 会跳过 null，达不到"全量替换"语义）
        assertTrue(sqlSet.contains("description") && sqlSet.contains("category") && sqlSet.contains("tags"),
                "全量替换应显式 set 全部字段，实际 SET 子句=" + sqlSet);

        verify(stringRedisTemplate).delete(CACHE_KEY);
    }

    @Test
    @DisplayName("更新工单：工单不存在时返回 10003，不执行更新")
    void updateThrowsWhenTicketMissing() {
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        TicketUpdateDTO dto = new TicketUpdateDTO();
        dto.setTitle("任意标题");
        dto.setPriority("LOW");
        dto.setOperatorId(OPERATOR_ID);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.update(TICKET_ID, dto));

        assertEquals(10003, exception.getErrorCode().getCode());
        verify(ticketMapper, never()).update(any(), any());
    }

    // ----------------------------------------------------------------- assign

    @Test
    @DisplayName("指派工单：待分配 → 流转到处理中，并清除详情缓存")
    void assignPendingTicketMovesToProcessing() {
        when(ticketMapper.selectById(TICKET_ID))
                .thenReturn(ticket(TICKET_ID, "PENDING_ASSIGN"))
                .thenReturn(ticket(TICKET_ID, "PROCESSING"));

        TicketVO result = ticketService.assign(TICKET_ID, assignDTO());

        assertEquals("PROCESSING", result.getStatus());
        verify(stringRedisTemplate).delete(CACHE_KEY);
        verify(ticketLogMapper).insert(ArgumentMatchers.<TicketLog>any());
    }

    @Test
    @DisplayName("指派工单：处理中再次指派为转派，只换处理人、状态不变")
    void assignProcessingTicketOnlyChangesAssignee() {
        when(ticketMapper.selectById(TICKET_ID))
                .thenReturn(ticket(TICKET_ID, "PROCESSING"))
                .thenReturn(ticket(TICKET_ID, "PROCESSING"));

        TicketVO result = ticketService.assign(TICKET_ID, assignDTO());

        assertEquals("PROCESSING", result.getStatus(), "转派不应改变状态");

        // 关键：写回库的参数里，处理人是新的、状态仍是 PROCESSING
        // （若误把状态写成 PENDING_ASSIGN，本断言会失败）
        ArgumentCaptor<Wrapper<Ticket>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(ticketMapper).update(isNull(), captor.capture());
        Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) captor.getValue()).getParamNameValuePairs();
        assertTrue(params.containsValue(ASSIGNEE_ID), "应写入新处理人，实际参数=" + params);
        assertTrue(params.containsValue("PROCESSING"), "转派时状态应仍为 PROCESSING，实际参数=" + params);
    }

    @Test
    @DisplayName("指派工单：已关闭工单被拒绝（10005），不修改任何数据")
    void assignClosedTicketIsRejected() {
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(TICKET_ID, "CLOSED"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.assign(TICKET_ID, assignDTO()));

        assertEquals(10005, exception.getErrorCode().getCode());
        verify(ticketMapper, never()).update(any(), any());
    }

    // ------------------------------------------------------------------ close

    @Test
    @DisplayName("关闭工单：待分配可直接关闭，并清除详情缓存")
    void closePendingTicketSucceeds() {
        when(ticketMapper.selectById(TICKET_ID))
                .thenReturn(ticket(TICKET_ID, "PENDING_ASSIGN"))
                .thenReturn(ticket(TICKET_ID, "CLOSED"));

        TicketVO result = ticketService.close(TICKET_ID, closeDTO());

        assertEquals("CLOSED", result.getStatus());
        verify(stringRedisTemplate).delete(CACHE_KEY);
        verify(ticketLogMapper).insert(ArgumentMatchers.<TicketLog>any());
    }

    @Test
    @DisplayName("关闭工单：处理中 → 已关闭不在白名单内，返回 10001")
    void closeProcessingTicketIsRejectedByStateMachine() {
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(TICKET_ID, "PROCESSING"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.close(TICKET_ID, closeDTO()));

        assertEquals(10001, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("PROCESSING") && exception.getMessage().contains("CLOSED"),
                "提示应包含起止状态，便于定位：实际=" + exception.getMessage());
        verify(ticketMapper, never()).update(any(), any());
    }

    // ----------------------------------------------------------------- resolve

    @Test
    @DisplayName("解决工单：处理中 → 已解决，并清除详情缓存、写入日志")
    void resolveProcessingTicketSucceeds() {
        when(ticketMapper.selectById(TICKET_ID))
                .thenReturn(ticket(TICKET_ID, "PROCESSING"))
                .thenReturn(ticket(TICKET_ID, "RESOLVED"));

        TicketVO result = ticketService.resolve(TICKET_ID, resolveDTO());

        assertEquals("RESOLVED", result.getStatus());
        verify(stringRedisTemplate).delete(CACHE_KEY);
        verify(ticketLogMapper).insert(ArgumentMatchers.<TicketLog>any());
    }

    @Test
    @DisplayName("解决工单：待分配 → 已解决属跳步，返回 10001 且不修改数据")
    void resolvePendingTicketIsRejectedByStateMachine() {
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(TICKET_ID, "PENDING_ASSIGN"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.resolve(TICKET_ID, resolveDTO()));

        assertEquals(10001, exception.getErrorCode().getCode());
        verify(ticketMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("解决工单：已解决再次解决属自身流转，返回 10001")
    void resolveResolvedTicketIsRejected() {
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(TICKET_ID, "RESOLVED"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.resolve(TICKET_ID, resolveDTO()));

        assertEquals(10001, exception.getErrorCode().getCode());
        verify(ticketMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("解决工单：工单不存在时返回 10003")
    void resolveUnknownTicketReturnsNotFound() {
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> ticketService.resolve(TICKET_ID, resolveDTO()));

        assertEquals(10003, exception.getErrorCode().getCode());
    }

    // ------------------------------------------- 环境与缓存故障（第13天边界）

    @Test
    @DisplayName("边界：Redis 不可用时查询详情降级为直接查库，接口仍可用")
    void getByIdFallsBackToDatabaseWhenRedisIsDown() {
        when(stringRedisTemplate.opsForValue())
                .thenThrow(new RedisConnectionFailureException("Unable to connect to 127.0.0.1:6379"));
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(TICKET_ID, "PROCESSING"));

        TicketVO result = ticketService.getById(TICKET_ID);

        assertEquals("PROCESSING", result.getStatus(),
                "Redis 不可用时应回源数据库，而不是让整个查询失败");
        verify(ticketMapper).selectById(TICKET_ID);
    }

    @Test
    @DisplayName("边界：回填缓存失败不影响查询结果")
    void getByIdStillReturnsDataWhenCacheWriteFails() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(TICKET_ID, "PENDING_ASSIGN"));
        doThrow(new RedisConnectionFailureException("write failed"))
                .when(valueOperations).set(eq(CACHE_KEY), any(), any());

        TicketVO result = ticketService.getById(TICKET_ID);

        assertEquals("PENDING_ASSIGN", result.getStatus(), "写缓存失败不应影响本次返回");
        verify(valueOperations).set(eq(CACHE_KEY), any(), any());
    }

    @Test
    @DisplayName("边界：Redis 不可用时关闭工单仍成功（库已改成功，删缓存失败不该报错）")
    void closeSucceedsWhenCacheEvictionFails() {
        when(ticketMapper.selectById(TICKET_ID))
                .thenReturn(ticket(TICKET_ID, "PENDING_ASSIGN"))
                .thenReturn(ticket(TICKET_ID, "CLOSED"));
        when(stringRedisTemplate.delete(CACHE_KEY))
                .thenThrow(new RedisConnectionFailureException("delete failed"));

        TicketVO result = ticketService.close(TICKET_ID, closeDTO());

        assertEquals("CLOSED", result.getStatus(),
                "删除缓存失败只影响一致性（TTL 会自愈），不应让已成功的写操作报错");
    }

    @Test
    @DisplayName("边界：数据库不可用时创建工单，异常向上冒泡（由全局处理器翻译为 50000）")
    void createPropagatesDatabaseFailure() {
        when(ticketMapper.insert(ArgumentMatchers.<Ticket>any()))
                .thenThrow(new DataAccessResourceFailureException("Connection refused"));

        // Service 层刻意不吞数据库异常：只有全局异常处理器才掌握"对外该说什么"，
        // 在此处 catch 会把系统级故障伪装成业务失败
        assertThrows(DataAccessResourceFailureException.class, () -> ticketService.create(createDTO()));
    }

    // ---------------------------------------------------------------- 辅助方法

    /** 模拟 MyBatis-Plus 的 insert：真实实现只回填主键，这里保持一致 */
    private void stubInsertAssigningId() {
        when(ticketMapper.insert(ArgumentMatchers.<Ticket>any())).thenAnswer(invocation -> {
            invocation.getArgument(0, Ticket.class).setId(TICKET_ID);
            return 1;
        });
    }

    private Ticket ticket(Long id, String status) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTicketNo("ST-20260923-120000-ABCD");
        ticket.setTitle("单元测试工单");
        ticket.setDescription("描述");
        ticket.setCategory("硬件");
        ticket.setPriority("MEDIUM");
        ticket.setStatus(status);
        ticket.setCreatorId(OPERATOR_ID);
        ticket.setCreatedAt(LocalDateTime.of(2026, 9, 23, 12, 0, 0));
        ticket.setUpdatedAt(LocalDateTime.of(2026, 9, 23, 12, 0, 0));
        return ticket;
    }

    private TicketCreateDTO createDTO() {
        TicketCreateDTO dto = new TicketCreateDTO();
        dto.setTitle("单元测试-创建工单");
        dto.setDescription("描述");
        dto.setCategory("硬件");
        dto.setPriority("HIGH");
        dto.setCreatorId(OPERATOR_ID);
        return dto;
    }

    private TicketAssignDTO assignDTO() {
        TicketAssignDTO dto = new TicketAssignDTO();
        dto.setAssigneeId(ASSIGNEE_ID);
        dto.setOperatorId(OPERATOR_ID);
        return dto;
    }

    private TicketCloseDTO closeDTO() {
        TicketCloseDTO dto = new TicketCloseDTO();
        dto.setOperatorId(OPERATOR_ID);
        return dto;
    }

    private TicketResolveDTO resolveDTO() {
        TicketResolveDTO dto = new TicketResolveDTO();
        dto.setOperatorId(OPERATOR_ID);
        return dto;
    }

}
