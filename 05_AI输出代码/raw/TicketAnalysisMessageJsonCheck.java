package com.smartticket.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

/**
 * 独立暂存验证：消息转为 JSON 后，字段完整且可以还原。
 * 不启动 Spring、不连接 RabbitMQ、不调用模型。
 */
public class TicketAnalysisMessageJsonCheck {

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TicketAnalysisMessage original = new TicketAnalysisMessage(
                1, "evt-demo-001", "task-demo-001", 123L, 0L,
                "trace-demo-001", "2026-10-12T13:00:00+08:00");

        // 发送端：Java 对象转换为 JSON。
        String json = mapper.writeValueAsString(original);
        JsonNode tree = mapper.readTree(json);
        Set<String> actualFields = new HashSet<>();
        tree.fieldNames().forEachRemaining(actualFields::add);
        Set<String> expectedFields = Set.of(
                "schemaVersion", "eventId", "taskId", "ticketId",
                "inputVersion", "traceId", "occurredAt");
        if (!expectedFields.equals(actualFields)) {
            throw new IllegalStateException("JSON fields do not match the message contract");
        }
        if (!tree.get("ticketId").isIntegralNumber()
                || !tree.get("schemaVersion").isIntegralNumber()
                || !tree.get("inputVersion").isIntegralNumber()) {
            throw new IllegalStateException("Numeric fields must remain JSON numbers");
        }

        // 接收端：JSON 转换回 Java 对象，再逐字段比较（record 自动生成 equals）。
        TicketAnalysisMessage restored = mapper.readValue(json, TicketAnalysisMessage.class);
        if (!original.equals(restored)) {
            throw new IllegalStateException("Message changed during the JSON round trip");
        }

        System.out.println(json);
        System.out.println("PASS: seven fields, numeric types, and round-trip values verified.");
    }
}
