package com.smartticket.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** AI 分析任务的交换机、队列和路由绑定。 */
@Configuration(proxyBeanMethods = false)
public class RabbitMqConfig {

    public static final String AI_EXCHANGE = "smartticket.ai";
    public static final String ANALYSIS_QUEUE = "smartticket.ai.analysis";
    public static final String ANALYSIS_ROUTING_KEY = "ticket.analysis.requested";

    @Bean
    public MessageConverter rabbitJsonMessageConverter(ObjectMapper objectMapper) {
        // 复用 Spring 配置的 ObjectMapper；只信任消息包中的类型信息。
        return new Jackson2JsonMessageConverter(objectMapper, "com.smartticket.mq");
    }

    @Bean
    public DirectExchange ticketAiExchange() {
        // 持久化交换机，不自动删除。
        return new DirectExchange(AI_EXCHANGE, true, false);
    }

    @Bean
    public Queue ticketAnalysisQueue() {
        // 持久化、非独占、不自动删除的业务队列。
        return new Queue(ANALYSIS_QUEUE, true, false, false);
    }

    @Bean
    public Binding ticketAnalysisBinding(
            @Qualifier("ticketAnalysisQueue") Queue queue,
            @Qualifier("ticketAiExchange") DirectExchange exchange) {
        // 路由键完全匹配时，将消息送入分析队列。
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(ANALYSIS_ROUTING_KEY);
    }
}
