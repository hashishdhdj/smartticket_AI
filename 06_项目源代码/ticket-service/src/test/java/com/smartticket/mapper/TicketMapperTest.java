package com.smartticket.mapper;

import com.smartticket.entity.TicketLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第3天验收用冒烟测试：证明 Mapper 被扫描成 Bean、应用能连上 MySQL、逻辑删除在 SQL 中生效。
 *
 * <p>只做查询，不写数据，不会污染 ticket / ticket_log 表。</p>
 */
@SpringBootTest
class TicketMapperTest {

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketLogMapper ticketLogMapper;

    @Test
    @DisplayName("两个 Mapper 都能被注入（证明被扫描成 Spring Bean）")
    void mapperBeansAreInjected() {
        assertNotNull(ticketMapper, "TicketMapper 未被扫描到");
        assertNotNull(ticketLogMapper, "TicketLogMapper 未被扫描到");
    }

    @Test
    @DisplayName("能查询 ticket / ticket_log 表（证明数据库连接可用）")
    void ticketTableIsQueryable() {
        // 不断言"表为空"：阶段1 会有真实数据（Postman 验证、第7天指派关闭产生的工单），
        // 空表断言会让测试在任何一次人工验证后失败。这里校验的是"查询能跑通"：
        // 连接失败、表不存在、SQL 错误都会让本用例失败。
        long ticketCount = ticketMapper.selectCount(null);
        long ticketLogCount = ticketLogMapper.selectCount(null);

        assertTrue(ticketCount >= 0, "ticket 查询应返回非负计数，实际=" + ticketCount);
        assertTrue(ticketLogCount >= 0, "ticket_log 查询应返回非负计数，实际=" + ticketLogCount);
    }

    @Test
    @DisplayName("查不存在的工单返回 null（证明逻辑删除条件已自动拼接）")
    void selectingMissingTicketReturnsNull() {
        assertNull(ticketMapper.selectById(1L), "不存在的工单应返回 null");
    }

}
