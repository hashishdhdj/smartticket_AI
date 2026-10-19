package com.smartticket.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段3 第6周验收测试：RabbitMQ 接入可用（章程第6周任务 1）。
 *
 * <p>与 {@code RedisConnectivityTest} 同构，两个用例守护两件不同的事：</p>
 * <ol>
 *   <li>配置是否被真正加载 —— {@code spring.rabbitmq} 前缀写错不会报错，
 *       只会静默套用默认值（127.0.0.1:5672 + guest/guest）。注意 host 与 port 的
 *       <b>默认值与本项目配置相同</b>，所以光断言它们抓不到前缀写错；
 *       真正有区分度的是 username / password / connection-timeout（默认 guest / guest / 60s）；</li>
 *   <li>连接是否可用 —— 真声明队列、真发送、真接收。</li>
 * </ol>
 *
 * <p>依赖 Docker 容器 {@code smartticket-rabbitmq}（端口 5672）。容器未启动时本类失败是预期行为，
 * 因为消息队列是第6周"异步执行 AI 分析"的前提，不应该被静默跳过。</p>
 *
 * <p>用匿名队列（exclusive + auto-delete）做收发：连接关闭即自动删除，不向 broker 留下任何残留，
 * 因此本用例可以反复跑、也不会与第5步的业务队列互相干扰。</p>
 */
@SpringBootTest
class RabbitConnectivityTest {

    @org.springframework.beans.factory.annotation.Value("${test.rabbit.port:5672}")
    private int expectedPort;

    @org.springframework.beans.factory.annotation.Value("${test.rabbit.host:127.0.0.1}")
    private String expectedHost;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitProperties rabbitProperties;

    @Test
    @DisplayName("application.yml 的 spring.rabbitmq 配置被加载（守护前缀写错）")
    void rabbitConfigIsLoadedFromYaml() {
        assertEquals(expectedHost, rabbitProperties.getHost());
        assertEquals(expectedPort, rabbitProperties.getPort());
        // 下面三条是"非默认值"，用来发现前缀误写成 spring.data.rabbitmq 这类静默失效：
        // 那种情况下读到的会是 guest / guest / 60s，本用例直接失败
        assertEquals("smartticket", rabbitProperties.getUsername());
        assertTrue(rabbitProperties.getPassword()!=null && !rabbitProperties.getPassword().isBlank()
                && !"guest".equals(rabbitProperties.getPassword()),"RabbitMQ credential must be configured");
        assertEquals(Duration.ofSeconds(3), rabbitProperties.getConnectionTimeout());
    }

    @Test
    @DisplayName("能连上 RabbitMQ 并完成 声明队列 → 发送 → 接收")
    void canDeclareSendAndReceive() {
        Queue queue = new AnonymousQueue();
        rabbitAdmin.declareQueue(queue);

        String payload = "ok-" + System.currentTimeMillis();
        // 交换机名传空串 = 走默认交换机，用队列名当路由键，无需预先声明任何业务队列
        rabbitTemplate.convertAndSend("", queue.getName(), payload);

        Object received = rabbitTemplate.receiveAndConvert(queue.getName(), 5000);
        assertEquals(payload, received, "发送后应能在 5 秒内收到同一内容");
    }

}
