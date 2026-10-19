package com.smartticket.knowledge;

import java.util.Map;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class KnowledgeQueueConfiguration {
    public static final String EXCHANGE = "smartticket.knowledge";
    public static final String QUEUE = "smartticket.knowledge.workflow";
    public static final String KEY = "knowledge.workflow.requested";
    public static final String DEAD_EXCHANGE = "smartticket.knowledge.dead";
    public static final String DEAD_QUEUE = "smartticket.knowledge.workflow.dead";

    @Bean
    public Declarables knowledgeTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var dead = new DirectExchange(DEAD_EXCHANGE, true, false);
        var queue = new Queue(QUEUE, true, false, false,
                Map.of("x-dead-letter-exchange", DEAD_EXCHANGE, "x-dead-letter-routing-key", "failed"));
        var deadQueue = new Queue(DEAD_QUEUE, true);
        return new Declarables(exchange, dead, queue, deadQueue,
                BindingBuilder.bind(queue).to(exchange).with(KEY),
                BindingBuilder.bind(deadQueue).to(dead).with("failed"));
    }
}
