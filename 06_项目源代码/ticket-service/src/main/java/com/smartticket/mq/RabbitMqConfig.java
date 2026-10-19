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

@Configuration(proxyBeanMethods = false)
public class RabbitMqConfig {

    public static final String AI_EXCHANGE = "smartticket.ai";
    public static final String ANALYSIS_QUEUE = "smartticket.ai.analysis";
    public static final String ANALYSIS_ROUTING_KEY = "ticket.analysis.requested";

    @Bean
    public DirectExchange analysisDeadExchange() {
        return new DirectExchange("smartticket.ai.dead", true, false);
    }

    @Bean
    public Queue analysisDeadQueue() {
        return new Queue("smartticket.ai.analysis.dead", true, false, false);
    }

    @Bean
    public Binding analysisDeadBinding() {
        return BindingBuilder.bind(analysisDeadQueue())
                .to(analysisDeadExchange())
                .with("ticket.analysis.failed");
    }

    @Bean
    public DirectExchange ticketAiExchange() {
        return new DirectExchange(AI_EXCHANGE, true, false);
    }

    @Bean
    public Queue ticketAnalysisQueue() {
        return new Queue(ANALYSIS_QUEUE, true, false, false);
    }

    @Bean
    public Binding ticketAnalysisBinding(
            @Qualifier("ticketAnalysisQueue") Queue queue,
            @Qualifier("ticketAiExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ANALYSIS_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitJsonMessageConverter(ObjectMapper objectMapper) {

        return new Jackson2JsonMessageConverter(objectMapper, "com.smartticket.mq");
    }
}
