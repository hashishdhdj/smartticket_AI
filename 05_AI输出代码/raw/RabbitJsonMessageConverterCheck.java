package com.smartticket.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 验证 JSON 转换器及 Spring Boot 装配，不连接 broker、不启动业务应用。 */
public class RabbitJsonMessageConverterCheck {
    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(RabbitMqConfig.class, RabbitAutoConfiguration.class);
            context.refresh();

            MessageConverter converter = context.getBean("rabbitJsonMessageConverter", MessageConverter.class);
            RabbitTemplate template = context.getBean(RabbitTemplate.class);
            if (template.getMessageConverter() != converter) {
                throw new IllegalStateException("RabbitTemplate did not use the JSON converter bean");
            }

            TicketAnalysisMessage original = new TicketAnalysisMessage(
                    1, "evt-demo-001", "task-demo-001", 123L, 0L,
                    "trace-demo-001", "2026-10-12T13:00:00+08:00");
            Message wireMessage = converter.toMessage(original, new MessageProperties());
            if (!MessageProperties.CONTENT_TYPE_JSON.equals(wireMessage.getMessageProperties().getContentType())) {
                throw new IllegalStateException("Message content type is not application/json");
            }
            Object restored = converter.fromMessage(wireMessage);
            if (!original.equals(restored)) {
                throw new IllegalStateException("JSON message round trip changed the value or Java type");
            }
            System.out.println("PASS: RabbitTemplate uses the JSON converter; content type and message round trip verified.");
        }
    }
}
