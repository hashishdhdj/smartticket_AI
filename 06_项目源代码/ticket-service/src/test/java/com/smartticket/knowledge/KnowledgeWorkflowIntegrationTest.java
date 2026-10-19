package com.smartticket.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.dto.*;
import com.smartticket.exception.ApiFailure;
import com.smartticket.mq.KnowledgeJobMessage;
import com.smartticket.rag.*;
import com.smartticket.security.ActorContext;
import com.smartticket.service.TicketService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "smartticket.knowledge.enabled=true","smartticket.knowledge.async-enabled=false",
    "smartticket.phase3.security-enabled=true","smartticket.phase3.record-tasks=false","smartticket.phase3.async-enabled=false",
    "smartticket.phase3.users[0].id=110902","smartticket.phase3.users[0].role=SUBMITTER","smartticket.phase3.users[0].token=workflow-submitter-key",
    "smartticket.phase3.users[1].id=220902","smartticket.phase3.users[1].role=HANDLER","smartticket.phase3.users[1].token=workflow-handler-key",
    "smartticket.phase3.users[2].id=990902","smartticket.phase3.users[2].role=ADMIN","smartticket.phase3.users[2].token=workflow-admin-key",
    "spring.rabbitmq.virtual-host=smartticket-knowledge-verify","smartticket.ai.enabled=false",
    "smartticket.vector.index-on-startup=false","smartticket.vector.embedding.base-url=http://127.0.0.1:11434"
})
@AutoConfigureMockMvc
class KnowledgeWorkflowIntegrationTest {
    @Autowired TicketService tickets;
    @SpyBean KnowledgeWorkflowRepository repo;
    @SpyBean KnowledgeOutboxRepository outbox;
    @SpyBean KnowledgeVectorRepository vectors;
    @SpyBean RabbitTemplate rabbit;
    @MockBean EmbeddingService embeddings;
    @Autowired KnowledgeWorker worker;
    @Autowired KnowledgeReviewService review;
    @Autowired KnowledgeWorkflowProperties config;
    @Autowired KnowledgeVectorProperties vectorConfig;
    @Autowired KnowledgeRetrievalService retrieval;
    @Autowired KnowledgeVisibility visibility;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    private final List<Long> ids=new ArrayList<>();
    private final List<String> resolutions=new ArrayList<>();
    private static final String MODEL="bge-m3:latest";
    private static final String QUESTION="合成演示缓存连接中断应如何恢复";
    private static final String ANSWER="先核对演示服务的连接配置，再重新建立连接并验证缓存读写恢复。";

    private void actor(long id,String role){ActorContext.set(new ActorContext.Actor(id,role));}
    private void admin(){actor(990902,"ADMIN");}
    private static float[] vector(){float[] result=new float[1024];Arrays.fill(result,1f/32f);return result;}
    private JdbcTemplate pg(){return (JdbcTemplate)ReflectionTestUtils.getField(vectors,"jdbcTemplate");}

    @BeforeEach
    void setup() {
        actor(110902,"SUBMITTER");config.setEnabled(true);config.setMaxAttempts(3);config.setAsyncEnabled(false);
        vectorConfig.getEmbedding().setBaseUrl("http://127.0.0.1:11434");
        when(embeddings.isAvailable()).thenReturn(true);when(embeddings.modelName()).thenReturn(MODEL);
        when(embeddings.embed(anyList())).thenAnswer(invocation->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return EmbeddingResult.ok(List.of(vector()),1,MODEL);
        });
    }

    @AfterEach
    void clean() {
        reset(repo,outbox,vectors,rabbit);ActorContext.clear();
        for(String id:resolutions) {
            vectors.deleteBySourcePrefix(new KnowledgeSource(id,1).prefix());
            pg().update("DELETE FROM knowledge_publication WHERE candidate_id=CAST(? AS uuid)",id);
        }
        for(long id:ids) {
            db.update("DELETE FROM cache_invalidation WHERE ticket_id=?",id);
            db.update("DELETE FROM ticket_log WHERE ticket_id=?",id);
            db.update("DELETE FROM ticket WHERE id=?",id); // Cascades only this test's workflow records.
        }
        while(rabbit.receive(KnowledgeQueueConfiguration.QUEUE,50)!=null){}
        while(rabbit.receive(KnowledgeQueueConfiguration.DEAD_QUEUE,50)!=null){}
    }

    private long processing() {
        actor(110902,"SUBMITTER");var create=new TicketCreateDTO();create.setTitle(QUESTION);create.setDescription("测试专用合成工单");create.setCreatorId(110902L);
        long id=tickets.create(create).getId();ids.add(id);
        admin();var assign=new TicketAssignDTO();assign.setAssigneeId(220902L);assign.setOperatorId(990902L);tickets.assign(id,assign);
        actor(220902,"HANDLER");return id;
    }

    private String resolved() {
        long ticket=processing();var resolve=new TicketResolveDTO();resolve.setOperatorId(220902L);resolve.setSolution(ANSWER);
        tickets.resolve(ticket,resolve);
        String id=db.queryForObject("SELECT resolution_id FROM ticket_resolution WHERE ticket_id=?",String.class,ticket);
        resolutions.add(id);return id;
    }

    private KnowledgeJobMessage message(String resolution,String kind,long revision) throws Exception {
        String payload=db.queryForObject("SELECT o.payload FROM knowledge_outbox o JOIN knowledge_job j ON j.job_id=o.job_id"
                + " WHERE j.resolution_id=? AND j.kind=? AND j.revision=? ORDER BY o.dispatch_sequence DESC LIMIT 1",
                String.class,resolution,kind,revision);
        return json.readValue(payload,KnowledgeJobMessage.class);
    }

    private String draft() throws Exception {
        String id=resolved();assertFalse(worker.process(message(id,"DRAFT",0)));admin();return id;
    }

    private String approved() throws Exception {
        String id=draft();review.approve(id,0L,QUESTION,ANSWER,"SHARED_DEMO",true);return id;
    }

    private String indexed() throws Exception {
        String id=approved();assertFalse(worker.process(message(id,"INDEX",1)));return id;
    }

    private String jobStatus(KnowledgeJobMessage m){return (String)repo.jobRow(m.jobId(),false).get("status");}

    private KnowledgeJobMessage due(KnowledgeJobMessage m) throws Exception {
        db.update("UPDATE knowledge_job SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE job_id=?",m.jobId());
        repo.recover();return message(m.resolutionId(),m.kind(),m.revision());
    }

    private KnowledgeOutboxRepository.Event claimed(KnowledgeJobMessage m) {
        String token=UUID.randomUUID().toString();
        assertEquals(1,db.update("UPDATE knowledge_outbox SET status='SENDING',lease_token=?,attempts=attempts+1,"
                + "lease_until=TIMESTAMPADD(SECOND,90,NOW(3)) WHERE event_id=?",token,m.eventId()));
        return new KnowledgeOutboxRepository.Event(m.eventId(),db.queryForObject("SELECT payload FROM knowledge_outbox WHERE event_id=?",String.class,m.eventId()),token,1);
    }

    @Test
    void resolveAtomicallyRecordsSnapshotJobAndOutboxWithoutEmbedding() throws Exception {
        String id=resolved();var m=message(id,"DRAFT",0);
        assertEquals(2L,db.queryForObject("SELECT input_version FROM ticket_resolution WHERE resolution_id=?",Long.class,id));
        assertEquals("PENDING",jobStatus(m));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_candidate WHERE candidate_id=?",Integer.class,id));
        verify(embeddings,never()).embed(anyList());
    }

    @Test
    void outboxFailureRollsBackResolutionStatusAndAudit() {
        long id=processing();doThrow(new IllegalStateException("outbox unavailable")).when(outbox).enqueue(anyString(),anyString(),anyString(),anyLong(),anyString(),anyInt());
        var dto=new TicketResolveDTO();dto.setOperatorId(220902L);dto.setSolution(ANSWER);
        assertThrows(IllegalStateException.class,()->tickets.resolve(id,dto));
        assertEquals("PROCESSING",db.queryForObject("SELECT status FROM ticket WHERE id=?",String.class,id));
        assertEquals(1L,db.queryForObject("SELECT version FROM ticket WHERE id=?",Long.class,id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ticket_resolution WHERE ticket_id=?",Integer.class,id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ticket_log WHERE ticket_id=? AND action='RESOLVE'",Integer.class,id));
    }

    @Test
    void legacyResolveAndCancelledCloseDoNotCreateKnowledge() {
        long first=processing();var resolve=new TicketResolveDTO();resolve.setOperatorId(220902L);tickets.resolve(first,resolve);
        actor(110902,"SUBMITTER");var dto=new TicketCreateDTO();dto.setTitle("取消工单");dto.setCreatorId(110902L);long second=tickets.create(dto).getId();ids.add(second);
        var close=new TicketCloseDTO();close.setOperatorId(110902L);tickets.close(second,close);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ticket_resolution WHERE ticket_id IN (?,?)",Integer.class,first,second));
    }

    @Test
    void duplicateDeliveryCreatesOneDraftAndNeverRevertsApproval() throws Exception {
        String id=draft();var draft=message(id,"DRAFT",0);review.approve(id,0L,QUESTION,ANSWER,"SHARED_DEMO",true);
        worker.process(draft);assertEquals("APPROVED",repo.candidate(id,false).state());
        assertEquals(1L,repo.candidate(id,false).revision());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM knowledge_candidate WHERE candidate_id=?",Integer.class,id));
        verify(embeddings,never()).embed(anyList());
    }

    @Test
    void reviewedIndexIsRetrievableAndTraceable() throws Exception {
        String id=approved();var m=message(id,"INDEX",1);worker.process(m);
        var source=new KnowledgeSource(id,1);
        assertEquals("SUCCEEDED",jobStatus(m));assertEquals(1L,repo.candidate(id,false).indexedRevision());
        assertTrue(retrieval.searchByKeyword(QUESTION).hits().stream().anyMatch(hit->source.file().equals(hit.chunk().sourceFile())));
        assertTrue(retrieval.searchByVector(QUESTION).hits().stream().anyMatch(hit->source.file().equals(hit.chunk().sourceFile())));
        assertEquals(1024,vectors.vectorDimensionsOf(source.file(),0));
        assertEquals(990902L,repo.candidate(id,false).reviewerId());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM knowledge_review_audit WHERE candidate_id=? AND action='APPROVE'",Integer.class,id));
    }

    @Test
    void unreviewedAndNotAcknowledgedSourcesAreNotExposed() throws Exception {
        String id=draft();var source=new KnowledgeSource(id,1);
        vectors.publish(source,QUESTION,ANSWER,vector(),MODEL); // Simulate a PG write ahead of the authoritative catalog.
        assertTrue(retrieval.searchByKeyword(QUESTION).hits().stream().noneMatch(hit->source.file().equals(hit.chunk().sourceFile())));
        review.approve(id,0L,QUESTION,ANSWER,"SHARED_DEMO",true);
        assertTrue(retrieval.searchByKeyword(QUESTION).hits().stream().noneMatch(hit->source.file().equals(hit.chunk().sourceFile())));
    }

    @Test
    void reviewRequiresAdminExplicitScopeAndRedaction() throws Exception {
        String id=draft();actor(220902,"HANDLER");
        assertEquals(403,assertThrows(ApiFailure.class,()->review.approve(id,0L,QUESTION,ANSWER,"SHARED_DEMO",true)).status());
        admin();
        assertEquals(400,assertThrows(ApiFailure.class,()->review.approve(id,0L,QUESTION,ANSWER,"PRIVATE",true)).status());
        assertEquals(400,assertThrows(ApiFailure.class,()->review.approve(id,0L,QUESTION,ANSWER,"SHARED_DEMO",false)).status());
        assertEquals(400,assertThrows(ApiFailure.class,()->review.approve(id,0L,QUESTION,"请联系测试人员 test@example.org 后处理连接问题","SHARED_DEMO",true)).status());
        assertEquals("DRAFT",repo.candidate(id,false).state());
    }

    @Test
    void reviewCasRejectsRepeatedOrStaleApprovals() throws Exception {
        String id=approved();assertEquals(409,assertThrows(ApiFailure.class,()->review.approve(id,0L,QUESTION,ANSWER,"SHARED_DEMO",true)).status());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM knowledge_job WHERE resolution_id=? AND kind='INDEX'",Integer.class,id));
    }

    @Test
    void rejectionIsAuditedAndProducesNoIndex() throws Exception {
        String id=draft();assertEquals("REJECTED",review.reject(id,0L).state());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_job WHERE resolution_id=? AND kind='INDEX'",Integer.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM knowledge_review_audit WHERE candidate_id=? AND action='REJECT'",Integer.class,id));
    }

    @Test
    void retractImmediatelyHidesSourceEvenWhenPgCleanupFails() throws Exception {
        String id=indexed();var source=new KnowledgeSource(id,1);review.retract(id,1L);
        doThrow(new IllegalStateException("PG unavailable")).when(vectors).retract(any(KnowledgeSource.class));
        var m=message(id,"DELETE",2);assertFalse(worker.process(m));assertEquals("PENDING",jobStatus(m));
        assertEquals(1024,vectors.vectorDimensionsOf(source.file(),0));
        assertTrue(retrieval.searchByKeyword(QUESTION).hits().stream().noneMatch(hit->source.file().equals(hit.chunk().sourceFile())));
        reset(vectors);worker.process(due(m));assertEquals(0,vectors.vectorDimensionsOf(source.file(),0));
    }

    @Test
    void mysqlAckFailureAfterPgCommitDoesNotEmbedTwice() throws Exception {
        String id=approved();var m=message(id,"INDEX",1);
        doThrow(new IllegalStateException("ack failure")).doCallRealMethod().when(repo).indexed(any());
        assertFalse(worker.process(m));assertEquals("PENDING",jobStatus(m));assertNull(repo.candidate(id,false).indexedRevision());
        assertTrue(retrieval.searchByKeyword(QUESTION).hits().stream().noneMatch(hit->new KnowledgeSource(id,1).file().equals(hit.chunk().sourceFile())));
        worker.process(due(m));assertEquals("SUCCEEDED",jobStatus(m));
        verify(embeddings,times(1)).embed(anyList());
    }

    @Test
    void duplicateSuccessAcknowledgesWithoutExtraEmbedding() throws Exception {
        String id=indexed();worker.process(message(id,"INDEX",1));verify(embeddings,times(1)).embed(anyList());
    }

    @Test
    void finiteRetriesAndAdminReplayPreserveAttemptHistory() throws Exception {
        String id=approved();var m=message(id,"INDEX",1);
        when(embeddings.embed(anyList())).thenReturn(EmbeddingResult.failed(com.smartticket.enums.ErrorCode.AI_CALL_FAILED,"offline",0,MODEL));
        assertFalse(worker.process(m));m=due(m);assertFalse(worker.process(m));m=due(m);assertTrue(worker.process(m));
        assertEquals("FAILED",jobStatus(m));String job=m.jobId();
        actor(110902,"SUBMITTER");assertThrows(ApiFailure.class,()->review.retry(job,1L));admin();
        review.retry(job,1L);when(embeddings.embed(anyList())).thenReturn(EmbeddingResult.ok(List.of(vector()),1,MODEL));
        worker.process(message(id,"INDEX",1));assertEquals("SUCCEEDED",jobStatus(m));
        assertEquals(4,((Number)repo.jobRow(job,false).get("total_attempts")).intValue());
        assertEquals(1,((Number)repo.jobRow(job,false).get("replays")).intValue());
        assertEquals(990902L,db.queryForObject("SELECT requester_id FROM knowledge_job_replay_audit WHERE job_id=?",Long.class,job));
    }

    @Test
    void expiredLeaseCannotCompleteAfterRecovery() throws Exception {
        String id=resolved();var m=message(id,"DRAFT",0);var old=repo.claim(m).orElseThrow();
        db.update("UPDATE knowledge_job SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE job_id=?",m.jobId());repo.recover();
        var current=repo.claim(message(id,"DRAFT",0)).orElseThrow();assertNotEquals(old.token(),current.token());
        repo.completeDraft(old);assertThrows(ApiFailure.class,()->repo.candidate(id,false));
        repo.completeDraft(current);assertEquals("DRAFT",repo.candidate(id,false).state());
    }

    @Test
    void lateIndexCannotReviveARetractedSource() throws Exception {
        String id=approved();var m=message(id,"INDEX",1);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(embeddings.embed(anyList())).thenAnswer(invocation->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return EmbeddingResult.ok(List.of(vector()),1,MODEL);});
        var pool=Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->worker.process(m));assertTrue(entered.await(5,TimeUnit.SECONDS));
            review.retract(id,1L);worker.process(message(id,"DELETE",2));release.countDown();future.get(10,TimeUnit.SECONDS);
            assertEquals("STALE",jobStatus(m));assertEquals("RETRACTED",repo.candidate(id,false).state());
            assertEquals(0,vectors.vectorDimensionsOf(new KnowledgeSource(id,1).file(),0));
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test
    void lateDeleteCannotEraseANewerPublication() throws Exception {
        String id=indexed();review.retract(id,1L);var oldDelete=message(id,"DELETE",2);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return invocation.callRealMethod();})
                .when(vectors).retract(any(KnowledgeSource.class));
        var pool=Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->worker.process(oldDelete));assertTrue(entered.await(5,TimeUnit.SECONDS));
            review.approve(id,2L,QUESTION,ANSWER,"SHARED_DEMO",true);worker.process(message(id,"INDEX",3));
            release.countDown();future.get(10,TimeUnit.SECONDS);
            assertEquals("STALE",jobStatus(oldDelete));assertEquals(3L,repo.candidate(id,false).indexedRevision());
            assertEquals(1024,vectors.vectorDimensionsOf(new KnowledgeSource(id,3).file(),0));
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test
    void pgReplacementIsAtomicAndOldRevisionsAreFenced() throws Exception {
        String id=draft();var first=new KnowledgeSource(id,1);vectors.publish(first,QUESTION,ANSWER,vector(),MODEL);
        assertThrows(Exception.class,()->vectors.publish(new KnowledgeSource(id,2),QUESTION,ANSWER,new float[]{1},MODEL));
        assertEquals(1024,vectors.vectorDimensionsOf(first.file(),0));
        assertTrue(vectors.retract(new KnowledgeSource(id,2)));
        assertFalse(vectors.publish(first,QUESTION,ANSWER,vector(),MODEL));
    }

    @Test
    void cloudEmbeddingConfigurationIsRejectedBeforeDispatch() throws Exception {
        String id=approved();vectorConfig.getEmbedding().setBaseUrl("https://example.org");
        worker.process(message(id,"INDEX",1));verify(embeddings,never()).embed(anyList());
        assertEquals("PENDING",jobStatus(message(id,"INDEX",1)));
    }

    @Test
    void fabricatedMessagesCannotChooseAnArbitraryTask() throws Exception {
        String id=resolved();var m=message(id,"DRAFT",0);
        var fake=new KnowledgeJobMessage(1,m.eventId(),m.jobId(),m.resolutionId(),m.kind(),m.revision(),m.traceId(),"2020-01-01T00:00:00Z");
        assertEquals(400,assertThrows(ApiFailure.class,()->worker.process(fake)).status());
        assertEquals("PENDING",jobStatus(m));
    }

    @Test
    void catalogOutageNeverExposesAPgOnlySource() throws Exception {
        String id=indexed();doThrow(new IllegalStateException("catalog unavailable")).when(repo).visibleSources(anySet());
        var result=retrieval.searchByKeyword(QUESTION);assertFalse(result.success());assertTrue(result.hits().isEmpty());
    }

    @Test
    void httpReviewAndResolutionStateEnforceRolesAndVersion() throws Exception {
        String id=draft();long ticket=repo.candidate(id,false).ticketId();
        String body=json.writeValueAsString(Map.of("expectedRevision",0,"question",QUESTION,"answer",ANSWER,"scope","SHARED_DEMO","redactionConfirmed",true));
        mvc.perform(post("/api/knowledge/candidates/"+id+"/approve").header("X-API-Key","workflow-handler-key").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/knowledge/candidates/"+id+"/approve").header("X-API-Key","workflow-admin-key").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("APPROVED"));
        mvc.perform(post("/api/knowledge/candidates/"+id+"/approve").header("X-API-Key","workflow-admin-key").contentType("application/json").content(body))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/tickets/"+ticket+"/knowledge").header("X-API-Key","workflow-submitter-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].resolutionId").value(id));
        mvc.perform(get("/api/knowledge/candidates/"+id).header("X-API-Key","workflow-submitter-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publisherFailureIsRetainedAndRealBrokerAckFollowsCommit() throws Exception {
        String id=resolved();var m=message(id,"DRAFT",0);var event=claimed(m);
        doThrow(new org.springframework.amqp.AmqpException("broker offline")).when(rabbit)
                .send(eq(KnowledgeQueueConfiguration.EXCHANGE),anyString(),any(Message.class),any(CorrelationData.class));
        assertFalse(worker.publish(event));assertEquals("PENDING",db.queryForObject("SELECT status FROM knowledge_outbox WHERE event_id=?",String.class,m.eventId()));
        reset(rabbit);assertTrue(worker.publish(claimed(m)));
        rabbit.execute(channel->{
            var response=channel.basicGet(KnowledgeQueueConfiguration.QUEUE,false);assertNotNull(response);
            var props=new MessageProperties();props.setDeliveryTag(response.getEnvelope().getDeliveryTag());
            try {worker.receive(json.readValue(response.getBody(),KnowledgeJobMessage.class),new Message(response.getBody(),props),channel);}
            catch(Exception failure){throw new IllegalStateException(failure);}
            assertEquals("SUCCEEDED",jobStatus(m));assertEquals("DRAFT",repo.candidate(id,false).state());return null;
        });
        assertNull(rabbit.receive(KnowledgeQueueConfiguration.QUEUE,50));
    }

    @Test
    void exhaustedConsumerDeliveryIsDeadLetteredByRealBroker() throws Exception {
        config.setMaxAttempts(1);String id=approved();var m=message(id,"INDEX",1);
        when(embeddings.embed(anyList())).thenReturn(EmbeddingResult.failed(com.smartticket.enums.ErrorCode.AI_CALL_FAILED,"offline",0,MODEL));
        assertTrue(worker.publish(claimed(m)));
        rabbit.execute(channel->{
            var response=channel.basicGet(KnowledgeQueueConfiguration.QUEUE,false);assertNotNull(response);
            var props=new MessageProperties();props.setDeliveryTag(response.getEnvelope().getDeliveryTag());
            try {worker.receive(json.readValue(response.getBody(),KnowledgeJobMessage.class),new Message(response.getBody(),props),channel);}
            catch(Exception failure){throw new IllegalStateException(failure);}
            return null;
        });
        var dead=rabbit.receive(KnowledgeQueueConfiguration.DEAD_QUEUE,3000);assertNotNull(dead);
        assertEquals(m.jobId(),json.readValue(dead.getBody(),KnowledgeJobMessage.class).jobId());
        assertEquals("FAILED",jobStatus(m));assertNull(rabbit.receive(KnowledgeQueueConfiguration.QUEUE,50));
    }

    @Test
    void unroutablePublicationIsNotMarkedSent() throws Exception {
        String id=resolved();var m=message(id,"DRAFT",0);
        rabbit.execute(channel->{channel.queueUnbind(KnowledgeQueueConfiguration.QUEUE,KnowledgeQueueConfiguration.EXCHANGE,KnowledgeQueueConfiguration.KEY);return null;});
        try {
            assertFalse(worker.publish(claimed(m)));
            assertEquals("PENDING",db.queryForObject("SELECT status FROM knowledge_outbox WHERE event_id=?",String.class,m.eventId()));
        } finally {
            rabbit.execute(channel->{channel.queueBind(KnowledgeQueueConfiguration.QUEUE,KnowledgeQueueConfiguration.EXCHANGE,KnowledgeQueueConfiguration.KEY);return null;});
        }
    }

    @Test
    void disabledWorkflowRejectsStructuredInputButKeepsLegacyResolve() {
        long id=processing();config.setEnabled(false);
        var resolve=new TicketResolveDTO();resolve.setOperatorId(220902L);resolve.setSolution(ANSWER);
        assertEquals(503,assertThrows(ApiFailure.class,()->tickets.resolve(id,resolve)).status());
        assertEquals("PROCESSING",db.queryForObject("SELECT status FROM ticket WHERE id=?",String.class,id));
        resolve.setSolution(null);assertEquals("RESOLVED",tickets.resolve(id,resolve).getStatus());
    }

    @Test
    void foreignSubmitterCannotReadResolutionMetadata() throws Exception {
        String id=draft();long ticket=repo.candidate(id,false).ticketId();
        db.update("UPDATE ticket SET creator_id=110999 WHERE id=?",ticket);
        actor(110902,"SUBMITTER");assertEquals(403,assertThrows(ApiFailure.class,()->review.ticketState(ticket)).status());
        mvc.perform(get("/api/knowledge/candidates")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentDraftDeliveryHasOneClaimAndOneCandidate() throws Exception {
        String id=resolved();var m=message(id,"DRAFT",0);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Boolean> work=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return worker.process(m);};
            var first=pool.submit(work);var second=pool.submit(work);start.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM knowledge_candidate WHERE candidate_id=?",Integer.class,id));
            assertEquals(1,((Number)repo.jobRow(m.jobId(),false).get("total_attempts")).intValue());
        } finally {start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
}
