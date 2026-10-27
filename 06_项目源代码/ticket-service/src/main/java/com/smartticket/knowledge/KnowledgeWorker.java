package com.smartticket.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.smartticket.exception.ApiFailure;
import com.smartticket.governance.ApplicationMetrics;
import com.smartticket.mq.KnowledgeJobMessage;
import com.smartticket.rag.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeWorker {
    private final KnowledgeWorkflowRepository repo;
    private final KnowledgeOutboxRepository outbox;
    private final KnowledgeWorkflowProperties config;
    private final KnowledgeVectorProperties vectorConfig;
    private final EmbeddingService embeddings;
    private final KnowledgeVectorRepository vectors;
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final RabbitListenerEndpointRegistry listeners;
    private final ApplicationMetrics metrics;
    private final AtomicLong pausedUntil=new AtomicLong();
    private final com.smartticket.experience.graph.ExperienceGraphSource graphSource;

    public KnowledgeWorker(KnowledgeWorkflowRepository repo,KnowledgeOutboxRepository outbox,
            KnowledgeWorkflowProperties config,KnowledgeVectorProperties vectorConfig,
            EmbeddingService embeddings,KnowledgeVectorRepository vectors,RabbitTemplate rabbit,
            ObjectMapper mapper,RabbitListenerEndpointRegistry listeners,ApplicationMetrics metrics,
            com.smartticket.experience.graph.ExperienceGraphSource graphSource) {
        this.repo=repo;this.outbox=outbox;this.config=config;this.vectorConfig=vectorConfig;
        this.embeddings=embeddings;this.vectors=vectors;this.rabbit=rabbit;this.mapper=mapper;this.listeners=listeners;this.metrics=metrics;
        this.graphSource=graphSource;
    }

    @Scheduled(fixedDelayString="${smartticket.knowledge.scheduler-delay-ms:5000}")
    public void tick() {
        if(!config.isEnabled()||!config.isAsyncEnabled()) return;
        try {
            repo.recover();
            if(pausedUntil.get()>0&&System.currentTimeMillis()>=pausedUntil.get()) {
                var listener=listeners.getListenerContainer("knowledge-workflow");
                if(listener!=null&&!listener.isRunning()) listener.start();
                pausedUntil.set(0);
            }
            for(int index=0;index<10;index++) {
                var event=outbox.claim();if(event.isEmpty()) break;
                publish(event.get());
            }
        } catch(Exception failure) { metrics.count("knowledge.scheduler","retry"); }
    }

    public boolean publish(KnowledgeOutboxRepository.Event event) {
        try {
            var correlation=new CorrelationData(event.id());
            var props=new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setMessageId(event.id());
            props.setHeader("__TypeId__",KnowledgeJobMessage.class.getName());
            rabbit.send(KnowledgeQueueConfiguration.EXCHANGE,KnowledgeQueueConfiguration.KEY,
                    new Message(event.payload().getBytes(StandardCharsets.UTF_8),props),correlation);
            var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
            if(!confirm.isAck()||correlation.getReturned()!=null) throw new IllegalStateException("Unconfirmed publication");
            outbox.sent(event);metrics.count("knowledge.outbox","sent");return true;
        } catch(Exception failure) {
            outbox.failed(event);metrics.count("knowledge.outbox","retry");return false;
        }
    }

    @RabbitListener(id="knowledge-workflow",queues=KnowledgeQueueConfiguration.QUEUE,ackMode="MANUAL",
            autoStartup="#{@knowledgeWorkflowProperties.enabled && @knowledgeWorkflowProperties.asyncEnabled}")
    public void receive(KnowledgeJobMessage message,Message raw,Channel channel) throws Exception {
        long tag=raw.getMessageProperties().getDeliveryTag();
        try {
            if(process(message)) channel.basicReject(tag,false);
            else channel.basicAck(tag,false);
        } catch(ApiFailure invalid) {
            channel.basicReject(tag,false);metrics.count("knowledge.message","invalid");
        } catch(Exception infrastructure) {
            channel.basicNack(tag,false,true);metrics.count("knowledge.message","infrastructure_failure");
            if(pausedUntil.compareAndSet(0,System.currentTimeMillis()+30000)) {
                var listener=listeners.getListenerContainer("knowledge-workflow");
                if(listener!=null) java.util.concurrent.CompletableFuture.runAsync(listener::stop);
            }
        }
    }

    public boolean process(KnowledgeJobMessage message) {
        validate(message);
        if(!config.isEnabled()) throw new ApiFailure(400,40000,"知识工作流未启用");
        var claimed=repo.claim(message);
        if(claimed.isEmpty()) {metrics.count("knowledge.job","duplicate");return false;}
        var job=claimed.get();
        try {
            if("DRAFT".equals(job.kind())) repo.completeDraft(job);
            else if(!repo.current(job)) repo.finish(job,"STALE","INPUT_CHANGED");
            else if("DELETE".equals(job.kind())) {
                boolean applied=vectors.retract(new KnowledgeSource(job.resolutionId(),job.revision()));
                repo.finish(job,applied?"SUCCEEDED":"STALE",applied?null:"PG_NEWER_REVISION");
            } else index(job);
            metrics.count("knowledge.job","processed");return false;
        } catch(Exception failure) {
            metrics.count("knowledge.job","retry");return repo.failed(job,"WORKFLOW_IO_FAILED");
        }
    }

    private void index(KnowledgeWorkflowRepository.Job job) throws Exception {
        var input=mapper.readValue(job.input(),KnowledgeWorkflowRepository.PublicationInput.class);
        String content=KnowledgeSource.content(input.question(),input.answer());
        if(!KnowledgeSource.hash(content).equals(input.hash())) throw new IllegalStateException("Input hash mismatch");
        var source=new KnowledgeSource(job.resolutionId(),job.revision());
        var graph=graphSource.plan(source,input.question(),input.answer(),input.hash()).orElse(null);
        requireLocalEmbedding();
        String model=embeddings.modelName();
        if(!vectors.publicationMatches(source,input.hash(),model)) {
            var models=vectors.distinctModels();
            if(!models.isEmpty()&&(!models.contains(model)||models.size()!=1))
                throw new IllegalStateException("Reindex all sources before changing embedding models");
            var embedded=embeddings.embed(List.of(content));
            if(!embedded.success()||embedded.embeddings().size()!=1||!model.equals(embedded.model()))
                throw new IllegalStateException("Local embedding unavailable");
            float[] vector=embedded.embeddings().get(0);
            if(vector.length!=1024) throw new IllegalStateException("Expected 1024 dimensions");
            double norm=0;
            for(float value:vector) {if(!Float.isFinite(value)) throw new IllegalStateException("Non-finite vector");norm+=value*value;}
            if(norm==0) throw new IllegalStateException("Empty vector");
            boolean published=graph==null?vectors.publish(source,input.question(),input.answer(),vector,model)
                    :vectors.publish(source,input.question(),input.answer(),vector,model,graph);
            if(!published) {
                repo.finish(job,"STALE","PG_NEWER_REVISION");return;
            }
        } else if(graph!=null&&!vectors.graphMatches(source,graph)&&!vectors.repairGraph(source,input.hash(),model,graph)) {
            repo.finish(job,"STALE","PG_NEWER_REVISION");return;
        }
        if(graph!=null&&!vectors.graphMatches(source,graph))throw new IllegalStateException("Graph publication incomplete");
        if(graph==null)repo.indexed(job);else repo.indexed(job,graph); // Both receipts commit with the current MySQL version.
    }

    private void requireLocalEmbedding() {
        URI uri=URI.create(vectorConfig.getEmbedding().getBaseUrl());
        if(!"http".equals(uri.getScheme())||!Set.of("127.0.0.1","localhost","::1","[::1]","ollama","host.docker.internal").contains(uri.getHost())
                ||uri.getPort()!=11434||!Set.of("bge-m3","bge-m3:latest").contains(embeddings.modelName()))
            throw new IllegalStateException("Knowledge workflow requires direct local Ollama BGE-M3");
    }

    public static void validate(KnowledgeJobMessage message) {
        try {
            if(message==null||message.schemaVersion()!=1||!Set.of("DRAFT","INDEX","DELETE").contains(message.kind())
                    ||message.revision()<0||("DRAFT".equals(message.kind())?message.revision()!=0:message.revision()==0))
                throw new IllegalArgumentException();
            for(String id:List.of(message.eventId(),message.jobId(),message.resolutionId(),message.traceId()))
                if(!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            OffsetDateTime.parse(message.occurredAt());
        } catch(Exception invalid) {throw new ApiFailure(400,40000,"非法知识消息");}
    }
}
