package com.smartticket.support;

import com.smartticket.mq.TicketAnalysisMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 使用独立临时队列验证 JSON 对象收发，不消费业务队列。 */
@SpringBootTest(properties = {
        "smartticket.ai.enabled=false",
        "smartticket.vector.index-on-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class RabbitJsonMessageTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Test
    @DisplayName("临时队列发送并接收JSON消息，核对全部7个字段")
    void messageRoundTripPreservesEveryField() {
        assertInstanceOf(Jackson2JsonMessageConverter.class,
                rabbitTemplate.getMessageConverter(), "发送模板必须使用JSON转换器");

        Queue queue = new AnonymousQueue();
        rabbitAdmin.declareQueue(queue);
        try {
            TicketAnalysisMessage sent = new TicketAnalysisMessage(
                    1, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    123L, 0L, UUID.randomUUID().toString(),
                    "2026-10-13T10:00:00+08:00");

            // 默认交换机以队列名作为路由键；只向本次临时队列发送。
            rabbitTemplate.convertAndSend("", queue.getName(), sent);

            // 最多等5秒；转换器将接收到的JSON还原为Java对象。
            Object received = rabbitTemplate.receiveAndConvert(queue.getName(), 5000);
            assertNotNull(received, "5秒内应收到测试消息");
            TicketAnalysisMessage actual = assertInstanceOf(
                    TicketAnalysisMessage.class, received, "JSON应还原成消息类");

            assertAll("交接单的7个字段都应保持一致",
                    () -> assertEquals(sent.schemaVersion(), actual.schemaVersion(), "schemaVersion"),
                    () -> assertEquals(sent.eventId(), actual.eventId(), "eventId"),
                    () -> assertEquals(sent.taskId(), actual.taskId(), "taskId"),
                    () -> assertEquals(sent.ticketId(), actual.ticketId(), "ticketId"),
                    () -> assertEquals(sent.inputVersion(), actual.inputVersion(), "inputVersion"),
                    () -> assertEquals(sent.traceId(), actual.traceId(), "traceId"),
                    () -> assertEquals(sent.occurredAt(), actual.occurredAt(), "occurredAt"));
        } finally {
            // 只删除本次创建的临时队列，不清空或修改任何业务队列。
            rabbitAdmin.deleteQueue(queue.getName());
        }
    }
}
