package com.smartticket.experience.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.dto.*;
import com.smartticket.exception.ApiFailure;
import com.smartticket.experience.*;
import com.smartticket.experience.ExperienceCardContract.*;
import com.smartticket.experience.ExperienceContract.*;
import com.smartticket.knowledge.*;
import com.smartticket.mq.KnowledgeJobMessage;
import com.smartticket.rag.*;
import com.smartticket.security.ActorContext;
import com.smartticket.service.TicketService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
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
import static com.smartticket.experience.graph.ExperienceGraph.*;

@SpringBootTest(properties={
    "smartticket.experience.enabled=true","smartticket.experience.cards-enabled=true","smartticket.experience.graph-index-enabled=true",
    "smartticket.knowledge.enabled=true","smartticket.knowledge.async-enabled=false",
    "smartticket.ai.enabled=false","smartticket.vector.index-on-startup=false","smartticket.vector.embedding.base-url=http://127.0.0.1:11434",
    "smartticket.phase3.record-tasks=false","smartticket.phase3.async-enabled=false","smartticket.phase3.security-enabled=true",
    "smartticket.phase3.users[0].id=110925","smartticket.phase3.users[0].role=SUBMITTER","smartticket.phase3.users[0].token=graph-submitter-key",
    "smartticket.phase3.users[1].id=220927","smartticket.phase3.users[1].role=HANDLER","smartticket.phase3.users[1].token=graph-handler-key",
    "smartticket.phase3.users[2].id=990925","smartticket.phase3.users[2].role=ADMIN","smartticket.phase3.users[2].token=graph-admin-key"
})
@AutoConfigureMockMvc
class ExperienceGraphIntegrationTest {
    @Autowired TicketService tickets;
    @Autowired ExperienceService notes;
    @Autowired ExperienceCardService cards;
    @Autowired ExperienceCardRepository cardRepo;
    @Autowired ExperienceProperties config;
    @Autowired KnowledgeReviewService review;
    @Autowired KnowledgeWorkflowRepository knowledge;
    @Autowired KnowledgeWorker worker;
    @Autowired KnowledgeVectorRepository vectors;
    @Autowired ExperienceGraphSource graphSource;
    @Autowired ExperienceGraphService graphs;
    @SpyBean ExperienceGraphStore store;
    @SpyBean ExperienceGraphReceiptRepository receipts;
    @MockBean EmbeddingService embeddings;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    private final List<Long> ticketsOwned=new ArrayList<>();
    private final List<String> candidates=new ArrayList<>();
    private final Set<String> cardIds=new HashSet<>(),nodesOwned=new HashSet<>(),edgesOwned=new HashSet<>();
    private String condition;
    private static final Evidence REF=new Evidence("docs/api/experience-cards.md","G3合成验证来源");
    private void actor(long id,String role){ActorContext.set(new ActorContext.Actor(id,role));}
    private void handler(){actor(220927,"HANDLER");}
    private void admin(){actor(990925,"ADMIN");}
    private JdbcTemplate pg(){return (JdbcTemplate)ReflectionTestUtils.getField(vectors,"jdbcTemplate");}
    private static float[] vector(){float[] v=new float[1024];Arrays.fill(v,1f/32f);return v;}
    @BeforeEach void setup(){
        // Keep unique fixture text from accidentally resembling an 11-digit customer phone number.
        config.setGraphIndexEnabled(true);condition="合成适用条件-"+UUID.randomUUID().toString().replaceAll("([0-9])","x$1x");
        when(embeddings.modelName()).thenReturn("bge-m3:latest");when(embeddings.isAvailable()).thenReturn(true);
        when(embeddings.embed(anyList())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return EmbeddingResult.ok(List.of(vector()),1,"bge-m3:latest");});
    }
    @AfterEach void clean(){
        reset(store,receipts);config.setGraphIndexEnabled(true);ActorContext.clear();
        for(String id:candidates){vectors.deleteBySourcePrefix(new KnowledgeSource(id,1).prefix());pg().update("DELETE FROM knowledge_publication WHERE candidate_id=CAST(? AS uuid)",id);}
        for(String id:edgesOwned)pg().update("DELETE FROM experience_edge WHERE edge_id=? AND NOT EXISTS(SELECT 1 FROM experience_edge_evidence WHERE edge_id=?)",id,id);
        for(String id:nodesOwned)pg().update("DELETE FROM experience_node WHERE node_id=? AND NOT EXISTS(SELECT 1 FROM experience_edge WHERE from_node=? OR to_node=?)",id,id,id);
        for(long id:ticketsOwned){cardIds.addAll(db.queryForList("SELECT card_id FROM experience_submission WHERE ticket_id=?",String.class,id));
            db.update("DELETE FROM cache_invalidation WHERE ticket_id=?",id);db.update("DELETE FROM ticket_log WHERE ticket_id=?",id);db.update("DELETE FROM ticket WHERE id=?",id);}
        for(String id:cardIds)db.update("DELETE FROM experience_card WHERE card_id=? AND NOT EXISTS(SELECT 1 FROM experience_submission WHERE card_id=?)"
                +" AND NOT EXISTS(SELECT 1 FROM knowledge_experience_version WHERE card_id=?)",id,id,id);
    }
    private long processing(){
        actor(110925,"SUBMITTER");var create=new TicketCreateDTO();create.setTitle("G3合成异常处理演示");create.setCreatorId(110925L);
        long id=tickets.create(create).getId();ticketsOwned.add(id);admin();var assign=new TicketAssignDTO();assign.setAssigneeId(220927L);assign.setOperatorId(990925L);
        tickets.assign(id,assign);handler();return id;
    }
    private Prepared prepare(long ticket,String condition){
        handler();long version=db.queryForObject("SELECT version FROM ticket WHERE id=?",Long.class,ticket);
        var n=notes.append(ticket,new InteractionInput(UUID.randomUUID().toString(),version,ExperienceContract.Kind.STAFF_REPLY,
                "合成员工排查：核对配置，再重试并记录结果。",Origin.AI_SYNTHETIC_DEMO,null,List.of(REF)));
        var c=new Card("G3合成账号异常排查",List.of("连接中断"),List.of(condition),List.of("核对连接配置","重试并记录结果"),
                List.of("未核实前不保证恢复"),List.of("错误做法：不核实配置就声称成功"),List.of("可能是配置问题，尚未证实"),List.of(REF),FactScope.PROJECT_RULE,SupportStatus.REFERENCED_DEMO);
        var p=cards.prepare(ticket,new Prepare(UUID.randomUUID().toString(),version,List.of(n.id()),List.of(),c));cardIds.add(p.card().id());return p;
    }
    private KnowledgeJobMessage message(String id,String kind,long revision)throws Exception {
        String payload=db.queryForObject("SELECT o.payload FROM knowledge_outbox o JOIN knowledge_job j ON j.job_id=o.job_id WHERE j.resolution_id=? AND j.kind=? AND j.revision=?"
                +" ORDER BY o.dispatch_sequence DESC LIMIT 1",String.class,id,kind,revision);return json.readValue(payload,KnowledgeJobMessage.class);
    }
    private String approved(Prepared p)throws Exception {
        handler();var dto=new TicketResolveDTO();dto.setOperatorId(220927L);dto.setSolution(p.card().answer());tickets.resolve(p.submission().ticketId(),dto);
        String id=db.queryForObject("SELECT resolution_id FROM ticket_resolution WHERE ticket_id=?",String.class,p.submission().ticketId());candidates.add(id);
        worker.process(message(id,"DRAFT",0));admin();cards.bind(id,new Binding(0L,p.submission().id()));
        review.approve(id,0L,p.card().question(),p.card().answer(),"SHARED_DEMO",true);remember(plan(id,1));return id;
    }
    private String published()throws Exception {String id=approved(prepare(processing(),condition));worker.process(message(id,"INDEX",1));return id;}
    private Plan plan(String id,long rev){var c=knowledge.candidate(id,false);return graphSource.plan(new KnowledgeSource(id,rev),c.question(),c.answer(),
            KnowledgeSource.hash(KnowledgeSource.content(c.question(),c.answer()))).orElseThrow();}
    private void remember(Plan p){p.nodes().forEach(n->nodesOwned.add(n.id()));p.edges().forEach(e->edgesOwned.add(e.id()));}
    private void retry(String id,long rev)throws Exception {
        db.update("UPDATE knowledge_job SET next_retry_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE resolution_id=? AND kind='INDEX' AND revision=?",id,rev);
        knowledge.recover();worker.process(message(id,"INDEX",rev));
    }
    private String jobStatus(String id,long rev){return db.queryForObject("SELECT status FROM knowledge_job WHERE resolution_id=? AND kind='INDEX' AND revision=?",String.class,id,rev);}
    private int evidence(String id){return pg().queryForObject("SELECT COUNT(*) FROM experience_edge_evidence WHERE candidate_id=CAST(? AS uuid)",Integer.class,id);}
    private void fails(int status,Runnable call){assertEquals(status,assertThrows(ApiFailure.class,call::run).status());}

    @Test void realPgStoresTypedNodesRelationsEvidenceAndMatchingMysqlReceipt()throws Exception {
        String id=published();Plan p=plan(id,1);var key=new KnowledgeSource(id,1);
        assertTrue(vectors.graphMatches(key,p));assertTrue(receipts.matches(key,p));assertEquals(p.evidence().size(),evidence(id));
        var actual=vectors.graph(key);assertEquals(p.nodes(),actual.nodes());assertEquals(p.edges(),actual.edges());
        assertEquals("READY",graphs.inspect(id).get("status"));assertEquals("SUCCEEDED",jobStatus(id,1));
        assertEquals(1024,vectors.vectorDimensionsOf(key.file(),0));
    }
    @Test void eachPgClaimKeepsExactCardTextVersionAndReferences()throws Exception {
        String id=published();var p=plan(id,1);var row=pg().queryForMap("SELECT * FROM experience_edge_evidence WHERE candidate_id=CAST(? AS uuid) LIMIT 1",id);
        assertEquals(p.cardHash(),row.get("card_hash"));assertEquals(p.textHash(),row.get("text_hash"));assertEquals(p.graphHash(),row.get("graph_hash"));
        assertEquals(new KnowledgeSource(id,1).file(),row.get("source_file"));assertTrue(row.get("details").toString().contains("references"));
    }
    @Test void noGraphExistsBeforeApprovalAndIndexing()throws Exception {
        var p=prepare(processing(),condition);assertEquals(0,pg().queryForObject("SELECT COUNT(*) FROM experience_graph_publication WHERE card_id=CAST(? AS uuid)",Integer.class,p.card().id()));
        String id=approved(p);assertEquals("NOT_VISIBLE",graphs.inspect(id).get("status"));assertEquals(0,evidence(id));
    }
    @Test void graphWriteFailureRollsBackPgTextCatalogAndConceptsTogether()throws Exception {
        String id=approved(prepare(processing(),condition));var key=new KnowledgeSource(id,1);var p=plan(id,1);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("injected after graph insert");}).when(store).replace(any(JdbcTemplate.class),eq(key),anyString(),any(Plan.class));
        worker.process(message(id,"INDEX",1));
        assertEquals("PENDING",jobStatus(id,1));assertEquals(0,vectors.vectorDimensionsOf(key.file(),0));assertEquals(0,evidence(id));
        assertEquals(0,pg().queryForObject("SELECT COUNT(*) FROM knowledge_publication WHERE candidate_id=CAST(? AS uuid)",Integer.class,id));
        var unique=p.nodes().stream().filter(n->n.key().equals(condition)).findFirst().orElseThrow();
        assertEquals(0,pg().queryForObject("SELECT COUNT(*) FROM experience_node WHERE node_id=?",Integer.class,unique.id()));
        assertNull(knowledge.candidate(id,false).indexedRevision());assertFalse(receipts.matches(key,p));
        reset(store);retry(id,1);assertEquals("READY",graphs.inspect(id).get("status"));
    }
    @Test void mysqlReceiptFailureRecoversPgCommitWithoutEmbeddingAgain()throws Exception {
        String id=approved(prepare(processing(),condition));var key=new KnowledgeSource(id,1);var p=plan(id,1);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("receipt unavailable");}).when(receipts).record(eq(key),any(Plan.class));
        worker.process(message(id,"INDEX",1));assertTrue(vectors.graphMatches(key,p));assertFalse(receipts.matches(key,p));
        assertNull(knowledge.candidate(id,false).indexedRevision());assertEquals("NOT_VISIBLE",graphs.inspect(id).get("status"));
        reset(receipts);retry(id,1);verify(embeddings,times(1)).embed(anyList());assertEquals("READY",graphs.inspect(id).get("status"));
    }
    @Test void duplicateDeliveryDoesNotAccumulateRelationsOrReceipts()throws Exception {
        String id=published();int count=evidence(id);worker.process(message(id,"INDEX",1));
        assertEquals(count,evidence(id));assertEquals(1,receipts.history(id).size());verify(embeddings,times(1)).embed(anyList());
    }
    @Test void missingGraphRowsRepairUsingExistingVectorWithoutModelCall()throws Exception {
        String id=published();var p=plan(id,1);var key=new KnowledgeSource(id,1);
        pg().update("DELETE FROM experience_edge_evidence WHERE candidate_id=CAST(? AS uuid) AND evidence_key=?",id,p.evidence().get(0).key());
        assertEquals("NOT_READY",graphs.inspect(id).get("status"));assertEquals("QUEUED",graphs.rebuild(id,1).get("status"));
        assertEquals("ALREADY_PENDING",graphs.rebuild(id,1).get("status"));worker.process(message(id,"INDEX",1));
        assertEquals("READY",graphs.inspect(id).get("status"));verify(embeddings,times(1)).embed(anyList());assertTrue(vectors.graphMatches(key,p));
    }
    @Test void graphBackfillForPreviouslyTextOnlyExperienceUsesSameRevision()throws Exception {
        var p=prepare(processing(),condition);String id=approved(p);config.setGraphIndexEnabled(false);worker.process(message(id,"INDEX",1));
        assertEquals("TEXT_ONLY",vectors.graph(new KnowledgeSource(id,1)).publication().state());assertTrue(receipts.history(id).isEmpty());
        fails(409,()->knowledge.replay(db.queryForObject("SELECT job_id FROM knowledge_job WHERE resolution_id=? AND kind='INDEX'",String.class,id),990925));
        config.setGraphIndexEnabled(true);assertEquals("QUEUED",graphs.rebuild(id,1).get("status"));worker.process(message(id,"INDEX",1));
        assertEquals(1,knowledge.candidate(id,false).revision());assertEquals("READY",graphs.inspect(id).get("status"));verify(embeddings,times(1)).embed(anyList());
    }
    @Test void alreadyCompleteRebuildIsIdempotentAndAuditedReplayOnlyOnce()throws Exception {
        String id=published();assertEquals("ALREADY_CURRENT",graphs.rebuild(id,1).get("status"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_job_replay_audit a JOIN knowledge_job j ON j.job_id=a.job_id WHERE j.resolution_id=?",Integer.class,id));
        pg().update("DELETE FROM experience_edge_evidence WHERE candidate_id=CAST(? AS uuid)",id);
        graphs.rebuild(id,1);graphs.rebuild(id,1);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM knowledge_job_replay_audit a JOIN knowledge_job j ON j.job_id=a.job_id WHERE j.resolution_id=?",Integer.class,id));
    }
    @Test void retractionImmediatelyHidesStillPresentPgGraph()throws Exception {
        String id=published();review.retract(id,1L);assertTrue(evidence(id)>0);
        var hidden=graphs.inspect(id);assertEquals("NOT_VISIBLE",hidden.get("status"));assertEquals(List.of(),hidden.get("nodes"));
        worker.process(message(id,"DELETE",2));assertEquals(0,evidence(id));assertEquals(0,vectors.vectorDimensionsOf(new KnowledgeSource(id,1).file(),0));
        assertEquals("RETRACTED",vectors.graph(new KnowledgeSource(id,2)).publication().state());
    }
    @Test void tombstoneRejectsLateIndexAndGraphOnlyRepair()throws Exception {
        String id=published();var p=plan(id,1);var key=new KnowledgeSource(id,1);var c=knowledge.candidate(id,false);
        review.retract(id,1L);worker.process(message(id,"DELETE",2));
        assertFalse(vectors.publish(key,c.question(),c.answer(),vector(),"bge-m3:latest",p));
        assertFalse(vectors.repairGraph(key,p.textHash(),"bge-m3:latest",p));assertEquals(0,evidence(id));
    }
    @Test void sharedRelationKeepsOtherSourceWhenOneCandidateIsRetracted()throws Exception {
        String first=published();String second=approved(prepare(processing(),condition));worker.process(message(second,"INDEX",1));
        var a=plan(first,1);var b=plan(second,1);assertEquals(a.graphHash(),b.graphHash());
        String shared=a.edges().stream().filter(e->e.relation()==Relation.REQUIRES).findFirst().orElseThrow().id();
        assertEquals(2,pg().queryForObject("SELECT COUNT(*) FROM experience_edge_evidence WHERE edge_id=?",Integer.class,shared));
        review.retract(first,1L);worker.process(message(first,"DELETE",2));
        assertEquals(1,pg().queryForObject("SELECT COUNT(*) FROM experience_edge_evidence WHERE edge_id=?",Integer.class,shared));
        assertEquals("READY",graphs.inspect(second).get("status"));assertTrue(vectors.graphMatches(new KnowledgeSource(second,1),b));
    }
    @Test void changedConditionAndScopeNeverInheritAnotherCardEvidence()throws Exception {
        String first=published();String second=approved(prepare(processing(),condition+"不可用"));worker.process(message(second,"INDEX",1));
        assertNotEquals(plan(first,1).graphHash(),plan(second,1).graphHash());
        assertFalse(vectors.graph(new KnowledgeSource(second,1)).nodes().stream().anyMatch(n->n.key().equals(condition)));
    }
    @Test void revisionReplacementKeepsOldReceiptButOnlyNewGraphEvidence()throws Exception {
        String id=published();long ticket=knowledge.candidate(id,false).ticketId();var old=plan(id,1);
        review.retract(id,1L);worker.process(message(id,"DELETE",2));var next=prepare(ticket,condition+"发生变化");admin();
        cards.bind(id,new Binding(2L,next.submission().id()));review.approve(id,3L,next.card().question(),next.card().answer(),"SHARED_DEMO",true);
        var fresh=plan(id,4);remember(fresh);worker.process(message(id,"INDEX",4));
        assertEquals(2,receipts.history(id).size());assertNotEquals(old.graphHash(),fresh.graphHash());
        assertEquals("READY",graphs.inspect(id).get("status"));assertTrue(vectors.graphMatches(new KnowledgeSource(id,4),fresh));
        assertEquals(0,pg().queryForObject("SELECT COUNT(*) FROM experience_edge_evidence WHERE candidate_id=CAST(? AS uuid) AND revision=1",Integer.class,id));
    }
    @Test void newFailureFeedbackSuppressesGraphBeforePgCleanup()throws Exception {
        String id=published();var v=cardRepo.version(id,1).orElseThrow();var s=cardRepo.submission(v.submissionId());
        handler();notes.feedback(s.ticketId(),new FeedbackInput(UUID.randomUUID().toString(),2L,cardRepo.positives(s.id()).get(0),Outcome.FAILED,
                FeedbackOrigin.SIMULATED_DEMO,"新增失败反馈，不能继续推荐原步骤。",null));admin();
        assertTrue(evidence(id)>0);assertEquals("NOT_VISIBLE",graphs.inspect(id).get("status"));fails(409,()->graphs.rebuild(id,1));
    }
    @Test void graphOffPreservesTextRetrievalAndRetractionStillCleansGraph()throws Exception {
        String id=published();config.setGraphIndexEnabled(false);fails(404,()->graphs.inspect(id));
        assertTrue(knowledge.visibleSources(Set.of(id)).contains(new KnowledgeSource(id,1).file()));
        review.retract(id,1L);worker.process(message(id,"DELETE",2));assertEquals(0,evidence(id));
    }
    @Test void wrongIdentityCannotInspectOrRebuildEvenTheirOwnTicket()throws Exception {
        String id=published();ActorContext.clear();String path="/api/knowledge/candidates/"+id+"/graph";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for(String token:List.of("graph-submitter-key","graph-handler-key")) {
            mvc.perform(get(path).header("X-API-Key",token)).andExpect(status().isForbidden());
            mvc.perform(post(path+"/rebuild").header("X-API-Key",token).contentType("application/json").content("{\"expectedRevision\":1}")).andExpect(status().isForbidden());
        }
        mvc.perform(get(path).header("X-API-Key","graph-admin-key")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"));
    }
    @Test void deletedTicketAndWrongVersionCannotExposeOrRebuild()throws Exception {
        String id=published();fails(409,()->graphs.rebuild(id,2));fails(400,()->graphs.inspect("not-a-uuid"));
        db.update("UPDATE ticket SET deleted=1 WHERE id=?",knowledge.candidate(id,false).ticketId());fails(404,()->graphs.inspect(id));
    }
    @Test void ordinaryLegacyKnowledgeCannotHaveInventedGraph()throws Exception {
        long ticket=processing();handler();var dto=new TicketResolveDTO();dto.setOperatorId(220927L);dto.setSolution("这是普通文本知识的合成解决说明，不提供结构卡。 ");tickets.resolve(ticket,dto);
        String id=db.queryForObject("SELECT resolution_id FROM ticket_resolution WHERE ticket_id=?",String.class,ticket);candidates.add(id);worker.process(message(id,"DRAFT",0));admin();
        review.approve(id,0L,"普通合成文本知识","这是普通文本知识的合成解决说明，不提供结构卡。","SHARED_DEMO",true);worker.process(message(id,"INDEX",1));
        assertEquals("NOT_EXPERIENCE",graphs.inspect(id).get("status"));fails(400,()->graphs.rebuild(id,1));
    }
    @Test void tamperedNodeIsNotReturnedAsReadyGraph()throws Exception {
        String id=published();var p=plan(id,1);var node=p.nodes().stream().filter(n->n.key().equals(condition)).findFirst().orElseThrow();
        pg().update("UPDATE experience_node SET label='篡改标签' WHERE node_id=?",node.id());
        assertFalse(vectors.graphMatches(new KnowledgeSource(id,1),p));assertEquals("NOT_READY",graphs.inspect(id).get("status"));
        pg().update("UPDATE experience_node SET label=? WHERE node_id=?",node.label(),node.id());
    }
    @Test void graphWriteOutsidePgTransactionIsRejected()throws Exception {
        String id=published();assertThrows(IllegalStateException.class,()->store.replace(pg(),new KnowledgeSource(id,1),plan(id,1).textHash(),plan(id,1)));
    }
    @Test void parallelDuplicateRebuildRequestsQueueOneReplay()throws Exception {
        String id=published();pg().update("DELETE FROM experience_edge_evidence WHERE candidate_id=CAST(? AS uuid)",id);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Object> call=()->{admin();try{start.await();return graphs.rebuild(id,1).get("status");}finally{ActorContext.clear();}};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();var statuses=Set.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertEquals(Set.of("QUEUED","ALREADY_PENDING"),statuses);
        }finally{pool.shutdownNow();}
    }
}
