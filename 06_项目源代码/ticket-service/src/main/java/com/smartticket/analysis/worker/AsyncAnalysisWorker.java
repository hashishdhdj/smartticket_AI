package com.smartticket.analysis.worker;

import com.smartticket.mq.outbox.OutboxRepository;

import com.smartticket.analysis.RuleClassifier;
import com.smartticket.analysis.persistence.AnalysisTaskRepository;
import com.smartticket.config.RuntimeProperties;
import com.smartticket.exception.ApiFailure;
import com.smartticket.governance.ApplicationMetrics;
import com.smartticket.governance.BudgetLedger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.smartticket.ai.TicketAnalysisService;
import com.smartticket.dto.TicketAnalyzeDTO;
import com.smartticket.mapper.TicketMapper;
import com.smartticket.mq.*;

import org.slf4j.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class AsyncAnalysisWorker {
    private static final Logger log = LoggerFactory.getLogger(AsyncAnalysisWorker.class);
    private final AnalysisTaskRepository repo;
    private final OutboxRepository outbox;
    private final RuntimeProperties config;
    private final RabbitTemplate rabbit;
    private final TicketMapper tickets;
    private final TicketAnalysisService analysis;
    private final ObjectMapper json;
    private final ApplicationMetrics metrics;
    @org.springframework.beans.factory.annotation.Autowired private BudgetLedger budget;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;

    private final java.util.concurrent.atomic.AtomicLong pausedUntil =
            new java.util.concurrent.atomic.AtomicLong();

    public AsyncAnalysisWorker(
            AnalysisTaskRepository repo,
            OutboxRepository outbox,
            RuntimeProperties config,
            RabbitTemplate rabbit,
            TicketMapper tickets,
            TicketAnalysisService analysis,
            ObjectMapper json,
            ApplicationMetrics metrics) {
        this.repo = repo;
        this.outbox = outbox;
        this.config = config;
        this.rabbit = rabbit;
        this.tickets = tickets;
        this.analysis = analysis;
        this.json = json;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${smartticket.phase3.scheduler-delay-ms:5000}")
    public void tick() {
        if (!config.isAsyncEnabled()) return;
        try {
            if (pausedUntil.get() > 0 && System.currentTimeMillis() >= pausedUntil.get()) {
                repo.verifyConnection();
                var container = listeners.getListenerContainer("stage3-analysis");
                if (container != null && !container.isRunning()) container.start();
                pausedUntil.set(0);
            }
            repo.recoverAndSchedule();
            for (int i = 0; i < 10; i++) {
                var next = outbox.claimEvent();
                if (next.isEmpty()) break;
                publish(next.get());
            }
        } catch (Exception e) {
            metrics.count("scheduler", "failed");
            log.warn("Async scheduler failed: {}", e.getClass().getSimpleName());
        }
    }

    public boolean publish(OutboxRepository.Event e) {
        try {
            CorrelationData correlation = new CorrelationData(e.id());
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setMessageId(e.id());
            props.setHeader("__TypeId__", TicketAnalysisMessage.class.getName());
            rabbit.send(
                    RabbitMqConfig.AI_EXCHANGE,
                    RabbitMqConfig.ANALYSIS_ROUTING_KEY,
                    new Message(
                            e.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8), props),
                    correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null)
                throw new IllegalStateException("Publication not confirmed or unroutable");
            outbox.published(e);
            metrics.count("outbox", "sent");
            return true;
        } catch (Exception failure) {
            outbox.publishFailed(e);
            metrics.count("outbox", "retry");
            return false;
        }
    }

    @RabbitListener(
            id = "stage3-analysis",
            queues = RabbitMqConfig.ANALYSIS_QUEUE,
            ackMode = "MANUAL",
            autoStartup = "${smartticket.phase3.async-enabled:true}")
    public void receive(TicketAnalysisMessage m, Message raw, Channel channel) throws Exception {
        long tag = raw.getMessageProperties().getDeliveryTag();
        try {
            validate(m);
            boolean exhausted = process(m);
            if (exhausted) channel.basicReject(tag, false);
            else channel.basicAck(tag, false);
        } catch (ApiFailure invalid) {
            metrics.count("analysis", "invalid");
            channel.basicReject(tag, false);
        } catch (Exception transientFailure) {
            metrics.count("analysis", "infrastructure_failure");
            channel.basicNack(tag, false, true);
            if (pausedUntil.compareAndSet(0, System.currentTimeMillis() + 30000)) {
                var container = listeners.getListenerContainer("stage3-analysis");
                if (container != null)
                    java.util.concurrent.CompletableFuture.runAsync(container::stop);
            }
            log.warn(
                    "Analysis infrastructure failure; listener paused for recovery | taskId={}",
                    m == null ? "invalid" : m.taskId());
        }
    }

    public boolean process(TicketAnalysisMessage m) {
        validate(m);
        var claimed = repo.claimTask(m);
        if (claimed.isEmpty()) {
            metrics.count("analysis", "duplicate");
            return false;
        }
        var task = claimed.get();
        var ticket = tickets.selectById(task.ticketId());
        if (ticket == null || ticket.getVersion() != task.version()) {
            repo.finish(task, null, "STALE");
            metrics.count("analysis", "stale");
            return false;
        }
        TicketAnalyzeDTO dto = new TicketAnalyzeDTO();
        dto.setTitle(ticket.getTitle());
        dto.setDescription(ticket.getDescription());
        long start = System.nanoTime();
        com.smartticket.vo.TicketAnalysisVO result;
        try {
            budget.request(ticket.getCreatorId());
            budget.reserve(ticket.getCreatorId(), 16000);
            result = analysis.analyze(dto);
        } catch (ApiFailure quota) {
            result =
                    com.smartticket.vo.TicketAnalysisVO.degraded(
                            com.smartticket.enums.ErrorCode.AI_BUDGET_EXHAUSTED,
                            "",
                            "rule-classify-v1",
                            0);
        }
        metrics.time("analysis.latency", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        if (result.degraded() && Objects.equals(result.degradedReasonCode(), 10007))
            return repo.retry(task, "AI_TRANSIENT");
        if (result.degraded())
            result =
                    RuleClassifier.fallback(
                            ticket.getTitle() + " " + String.valueOf(ticket.getDescription()),
                            result);
        repo.finish(task, result, null);
        metrics.count("analysis", result.degraded() ? "degraded" : "success");
        log.info(
                "Analysis completed | taskId={} | traceId={} | degraded={} | costMs={}",
                m.taskId(),
                m.traceId(),
                result.degraded(),
                result.costMs());
        return false;
    }

    public static void validate(TicketAnalysisMessage m) {
        if (m == null
                || m.schemaVersion() != 1
                || m.ticketId() == null
                || m.ticketId() <= 0
                || m.inputVersion() < 0) throw new ApiFailure(400, 40000, "非法任务消息");
        try {
            UUID.fromString(m.eventId());
            UUID.fromString(m.taskId());
            UUID.fromString(m.traceId());
            java.time.OffsetDateTime.parse(m.occurredAt());
        } catch (Exception e) {
            throw new ApiFailure(400, 40000, "非法任务标识或时间");
        }
    }
}
