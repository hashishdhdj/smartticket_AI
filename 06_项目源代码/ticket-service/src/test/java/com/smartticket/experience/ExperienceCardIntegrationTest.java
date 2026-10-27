package com.smartticket.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.dto.*;
import com.smartticket.exception.ApiFailure;
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
import static com.smartticket.experience.ExperienceCardContract.*;
import static com.smartticket.experience.ExperienceContract.*;

@SpringBootTest(properties={
    "smartticket.experience.enabled=true","smartticket.experience.cards-enabled=true",
    "smartticket.knowledge.enabled=true","smartticket.knowledge.async-enabled=false",
    "smartticket.ai.enabled=false","smartticket.vector.index-on-startup=false",
    "smartticket.vector.embedding.base-url=http://127.0.0.1:11434",
    "smartticket.phase3.record-tasks=false","smartticket.phase3.async-enabled=false","smartticket.phase3.security-enabled=true",
    "smartticket.phase3.users[0].id=110924","smartticket.phase3.users[0].role=SUBMITTER","smartticket.phase3.users[0].token=card-submitter-key",
    "smartticket.phase3.users[1].id=220925","smartticket.phase3.users[1].role=HANDLER","smartticket.phase3.users[1].token=card-handler-key",
    "smartticket.phase3.users[2].id=990924","smartticket.phase3.users[2].role=ADMIN","smartticket.phase3.users[2].token=card-admin-key",
    "smartticket.phase3.users[3].id=220926","smartticket.phase3.users[3].role=HANDLER","smartticket.phase3.users[3].token=card-other-key"
})
@AutoConfigureMockMvc
class ExperienceCardIntegrationTest {
    @Autowired TicketService tickets;
    @Autowired ExperienceService notes;
    @Autowired ExperienceCardService cards;
    @Autowired ExperienceCardRepository repo;
    @Autowired ExperienceProperties config;
    @Autowired KnowledgeReviewService review;
    @SpyBean KnowledgeWorkflowRepository knowledge;
    @Autowired KnowledgeWorker worker;
    @Autowired KnowledgeVectorRepository vectors;
    @Autowired KnowledgeRetrievalService retrieval;
    @SpyBean KnowledgeOutboxRepository outbox;
    @MockBean EmbeddingService embeddings;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    private final List<Long> ids=new ArrayList<>();
    private final Set<String> cardIds=new HashSet<>();
    private final List<String> resolutions=new ArrayList<>();
    private long ticket;
    private static final Evidence REF=new Evidence("docs/api/staff-experience.md","合成记录操作说明");
    private void actor(long id,String role){ActorContext.set(new ActorContext.Actor(id,role));}
    private void handler(){actor(220925,"HANDLER");}
    private void admin(){actor(990924,"ADMIN");}
    private long current(long id){return db.queryForObject("SELECT version FROM ticket WHERE id=?",Long.class,id);}
    private long processing(){
        actor(110924,"SUBMITTER");var create=new TicketCreateDTO();create.setTitle("G2合成处理记录");create.setCreatorId(110924L);
        long id=tickets.create(create).getId();ids.add(id);admin();var assign=new TicketAssignDTO();
        assign.setOperatorId(990924L);assign.setAssigneeId(220925L);tickets.assign(id,assign);handler();return id;
    }
    @BeforeEach void setup(){
        config.setEnabled(true);config.setCardsEnabled(true);
        when(embeddings.modelName()).thenReturn("bge-m3:latest");when(embeddings.isAvailable()).thenReturn(true);
        when(embeddings.embed(anyList())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            float[] v=new float[1024];Arrays.fill(v,1f/32f);return EmbeddingResult.ok(List.of(v),1,"bge-m3:latest");
        });ticket=processing();
    }
    @AfterEach void clean(){
        reset(outbox,knowledge);config.setCardsEnabled(true);ActorContext.clear();
        for(String id:resolutions){vectors.deleteBySourcePrefix(new KnowledgeSource(id,1).prefix());
            ((JdbcTemplate)ReflectionTestUtils.getField(vectors,"jdbcTemplate")).update("DELETE FROM knowledge_publication WHERE candidate_id=CAST(? AS uuid)",id);}
        for(long id:ids){
            cardIds.addAll(db.queryForList("SELECT card_id FROM experience_submission WHERE ticket_id=?",String.class,id));
            db.update("DELETE FROM cache_invalidation WHERE ticket_id=?",id);db.update("DELETE FROM ticket_log WHERE ticket_id=?",id);db.update("DELETE FROM ticket WHERE id=?",id);
        }
        for(String id:cardIds)db.update("DELETE FROM experience_card WHERE card_id=? AND NOT EXISTS(SELECT 1 FROM experience_submission WHERE card_id=?)"
                +" AND NOT EXISTS(SELECT 1 FROM knowledge_experience_version WHERE card_id=?)",id,id,id);
    }
    private Card card(String condition){return new Card("演示巡检连接异常处理",List.of("连接中断"),List.of(condition),List.of("先核对连接配置再重试，并记录是否恢复"),
            List.of("未核实前不承诺恢复"),List.of(),List.of(),List.of(REF),FactScope.PROJECT_RULE,SupportStatus.REFERENCED_DEMO);}
    private long note(long id){return notes.append(id,new InteractionInput(UUID.randomUUID().toString(),current(id),Kind.STAFF_REPLY,
            "合成员工建议：先核对连接配置，再重试并记录结果。",Origin.AI_SYNTHETIC_DEMO,null,List.of(REF))).id();}
    private Prepare input(long id,long note,Card card){return new Prepare(UUID.randomUUID().toString(),current(id),List.of(note),List.of(),card);}
    private Prepared prepare(long id,Card card){var p=cards.prepare(id,input(id,note(id),card));cardIds.add(p.card().id());return p;}
    private void fails(int status,Runnable call){assertEquals(status,assertThrows(ApiFailure.class,call::run).status());}
    private KnowledgeJobMessage message(String id,String kind,long revision)throws Exception {
        String payload=db.queryForObject("SELECT o.payload FROM knowledge_outbox o JOIN knowledge_job j ON j.job_id=o.job_id"
                +" WHERE j.resolution_id=? AND j.kind=? AND j.revision=? ORDER BY o.dispatch_sequence DESC LIMIT 1",String.class,id,kind,revision);
        return json.readValue(payload,KnowledgeJobMessage.class);
    }
    private String candidate(Prepared p)throws Exception {
        handler();var dto=new TicketResolveDTO();dto.setOperatorId(220925L);dto.setSolution(p.card().answer());tickets.resolve(p.submission().ticketId(),dto);
        String id=db.queryForObject("SELECT resolution_id FROM ticket_resolution WHERE ticket_id=?",String.class,p.submission().ticketId());
        resolutions.add(id);worker.process(message(id,"DRAFT",0));admin();cards.bind(id,new Binding(0L,p.submission().id()));return id;
    }
    private void approve(String id,long revision,StoredCard card){admin();review.approve(id,revision,card.question(),card.answer(),"SHARED_DEMO",true);}
    @Test void realHttpCreatesCardAndKeepsTicketUnresolvedAndSourceTraceable()throws Exception {
        long n=note(ticket);var body=input(ticket,n,card("仅限演示环境"));ActorContext.clear();
        String result=mvc.perform(post("/api/tickets/"+ticket+"/experience-cards").header("X-API-Key","card-handler-key")
                .contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.extractionMethod").value("provided-fields-v1")).andReturn().getResponse().getContentAsString();
        var data=json.readTree(result).path("data");cardIds.add(data.path("card").path("id").asText());
        assertEquals("PROCESSING",db.queryForObject("SELECT status FROM ticket WHERE id=?",String.class,ticket));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ticket_resolution WHERE ticket_id=?",Integer.class,ticket));
        handler();var detail=cards.detail(ticket,data.path("submission").path("id").asText());
        assertTrue(json.writeValueAsString(detail.get("evidence")).contains(Long.toString(n)));
    }
    @Test void exactContentDeduplicatesAcrossTicketsButProvenanceStaysSeparate(){
        var a=prepare(ticket,card("原手机号可用"));long other=processing();var b=prepare(other,card("原手机号可用"));
        assertEquals(a.card().id(),b.card().id());assertTrue(b.exactDuplicate());assertNotEquals(a.submission().id(),b.submission().id());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM experience_submission WHERE card_id=?",Integer.class,a.card().id()));
    }
    @Test void changedConditionGetsNewCardAndOnlyAdminSeesLexicalHint(){
        var a=prepare(ticket,card("原手机号可用"));var b=prepare(ticket,card("原手机号不可用"));
        assertNotEquals(a.card().id(),b.card().id());assertTrue(b.relatedDrafts().isEmpty());
        admin();var detail=cards.detail(ticket,b.submission().id());var prepared=(Prepared)detail.get("prepared");
        assertTrue(prepared.relatedDrafts().stream().anyMatch(h->h.cardId().equals(a.card().id())));
    }
    @Test void requestReplayIsStableAfterTicketVersionChangesAndConflictDoesNotOverwrite(){
        long n=note(ticket);var request=input(ticket,n,card("仅限演示环境"));var a=cards.prepare(ticket,request);cardIds.add(a.card().id());
        db.update("UPDATE ticket SET version=version+1 WHERE id=?",ticket);
        assertEquals(a.submission(),cards.prepare(ticket,request).submission());
        fails(409,()->cards.prepare(ticket,new Prepare(request.requestId(),request.expectedVersion(),List.of(n),List.of(),card("不同适用条件"))));
        fails(409,()->cards.prepare(ticket,new Prepare(UUID.randomUUID().toString(),1L,List.of(n),List.of(),request.card())));
    }
    @Test void concurrentCrossTicketSubmissionsDeduplicateInDatabase()throws Exception {
        long a=ticket,na=note(a),b=processing(),nb=note(b);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Prepared> ca=()->{handler();try{start.await();return cards.prepare(a,new Prepare(UUID.randomUUID().toString(),1L,List.of(na),List.of(),card("相同合成适用条件")));}finally{ActorContext.clear();}};
            Callable<Prepared> cb=()->{handler();try{start.await();return cards.prepare(b,new Prepare(UUID.randomUUID().toString(),1L,List.of(nb),List.of(),card("相同合成适用条件")));}finally{ActorContext.clear();}};
            var fa=pool.submit(ca);var fb=pool.submit(cb);start.countDown();var pa=fa.get(20,TimeUnit.SECONDS);var pb=fb.get(20,TimeUnit.SECONDS);
            cardIds.add(pa.card().id());cardIds.add(pb.card().id());assertEquals(pa.card().id(),pb.card().id());assertNotEquals(pa.exactDuplicate(),pb.exactDuplicate());
        }finally{pool.shutdownNow();}
    }
    @Test void realStaffPrivateRecordCannotBeSharedAsSyntheticKnowledge(){
        long n=notes.append(ticket,new InteractionInput(UUID.randomUUID().toString(),1L,Kind.STAFF_REPLY,"实际员工观察不自动共享。",Origin.STAFF_ENTERED,null,List.of(REF))).id();
        fails(400,()->cards.prepare(ticket,input(ticket,n,card("仅限演示环境"))));
    }
    @Test void failedAttemptCannotBePositiveEvidence(){
        long n=note(ticket);notes.feedback(ticket,new FeedbackInput(UUID.randomUUID().toString(),1L,n,Outcome.FAILED,FeedbackOrigin.SIMULATED_DEMO,"合成观察：此建议失败。",null));
        fails(400,()->cards.prepare(ticket,input(ticket,n,card("仅限演示环境"))));
    }
    @Test void correctionRequiresExplicitFailureRecordAndKeepsSnapshot(){
        long wrong=note(ticket);long corrected=notes.append(ticket,new InteractionInput(UUID.randomUUID().toString(),1L,Kind.CORRECTION,
                "更正：应先核实完整连接字段，不可仅重试。",Origin.AI_SYNTHETIC_DEMO,wrong,List.of(REF))).id();
        notes.feedback(ticket,new FeedbackInput(UUID.randomUUID().toString(),1L,wrong,Outcome.CORRECTED,FeedbackOrigin.SIMULATED_DEMO,"原建议已由新记录纠正。",corrected));
        fails(400,()->cards.prepare(ticket,input(ticket,corrected,card("仅限演示环境"))));
        var c=card("仅限演示环境");c=new Card(c.problemType(),c.symptoms(),c.conditions(),c.steps(),c.contraindications(),List.of("错误做法：不核实配置就重复重试"),c.hypotheses(),c.evidenceRefs(),c.factScope(),c.supportStatus());
        var p=cards.prepare(ticket,new Prepare(UUID.randomUUID().toString(),1L,List.of(corrected),List.of(wrong),c));cardIds.add(p.card().id());
        assertTrue(json.valueToTree(repo.snapshot(p.submission().id())).toString().contains("CORRECTED"));
    }
    @Test void foreignTicketEvidenceAndUndeclaredReferencesAreRejected(){
        long foreign=note(processing());fails(404,()->cards.prepare(ticket,input(ticket,foreign,card("仅限演示环境"))));
        var c=card("仅限演示环境");var forged=new Card(c.problemType(),c.symptoms(),c.conditions(),c.steps(),c.contraindications(),c.counterexamples(),c.hypotheses(),
                List.of(new Evidence("invented/policy.md","不存在的政策")),c.factScope(),c.supportStatus());
        long n=note(ticket);fails(400,()->cards.prepare(ticket,input(ticket,n,forged)));
    }
    @Test void submitterOtherHandlerAndForgedFieldsRejectedAtHttpBoundary()throws Exception {
        long n=note(ticket);var request=input(ticket,n,card("仅限演示环境"));ActorContext.clear();
        for(String token:List.of("card-submitter-key","card-other-key"))mvc.perform(post("/api/tickets/"+ticket+"/experience-cards")
                .header("X-API-Key",token).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isForbidden());
        var forged=json.valueToTree(request);((com.fasterxml.jackson.databind.node.ObjectNode)forged).put("authorId",990924);
        mvc.perform(post("/api/tickets/"+ticket+"/experience-cards").header("X-API-Key","card-handler-key")
                .contentType("application/json").content(forged.toString())).andExpect(status().isBadRequest());
    }
    @Test void disabledCardsAndReassignedTicketCannotExposeStoredProvenance(){
        var p=prepare(ticket,card("仅限演示环境"));config.setCardsEnabled(false);fails(404,()->cards.detail(ticket,p.submission().id()));config.setCardsEnabled(true);
        db.update("UPDATE ticket SET assignee_id=220926 WHERE id=?",ticket);fails(403,()->cards.detail(ticket,p.submission().id()));
    }
    @Test void bindingRequiresAdminAndSameTicketCandidate()throws Exception {
        var p=prepare(ticket,card("仅限演示环境"));String id=candidate(p);handler();fails(403,()->cards.bind(id,new Binding(0L,p.submission().id())));
        var foreign=prepare(processing(),card("另一合成适用条件"));admin();fails(400,()->cards.bind(id,new Binding(0L,foreign.submission().id())));
    }
    @Test void approvalBindsStructureTextAndOutboxToOneRevision()throws Exception {
        var p=prepare(ticket,card("仅限演示环境"));String id=candidate(p);assertEquals(0,repo.version(id,0).orElseThrow().revision());
        approve(id,0,p.card());var v=repo.version(id,1).orElseThrow();
        assertEquals(p.card().hash(),v.cardHash());assertEquals("APPROVED",v.state());
        assertEquals(v.publicationHash(),db.queryForObject("SELECT content_hash FROM knowledge_candidate WHERE candidate_id=?",String.class,id));
        assertTrue(db.queryForObject("SELECT input_json FROM knowledge_job WHERE resolution_id=? AND kind='INDEX'",String.class,id).contains(v.publicationHash()));
        assertEquals(repo.version(id,1).orElseThrow(),cards.bind(id,new Binding(1L,p.submission().id())));
    }
    @Test void approvalRejectsTextStructureDriftEvenWhenCreationFeatureDisabled()throws Exception {
        var p=prepare(ticket,card("仅限演示环境"));String id=candidate(p);config.setCardsEnabled(false);
        fails(400,()->review.approve(id,0L,p.card().question(),"另一个结论，不再与结构化条件保持一致。","SHARED_DEMO",true));
        assertTrue(repo.version(id,1).isEmpty());assertEquals("DRAFT",knowledge.candidate(id,false).state());
    }
    @Test void unverifiedCardStaysDraftAndCannotPublish()throws Exception {
        var c=card("未知具体到账时刻");c=new Card(c.problemType(),c.symptoms(),c.conditions(),c.steps(),c.contraindications(),c.counterexamples(),c.hypotheses(),c.evidenceRefs(),c.factScope(),SupportStatus.UNVERIFIED);
        var p=prepare(ticket,c);String id=candidate(p);fails(400,()->approve(id,0,p.card()));
        assertTrue(repo.version(id,1).isEmpty());assertNull(repo.reusable(p.card().id()));
    }
    @Test void failureFeedbackAfterPreparationBlocksApproval()throws Exception {
        var p=prepare(ticket,card("仅限演示环境"));String id=candidate(p);handler();
        long n=repo.positives(p.submission().id()).get(0);notes.feedback(ticket,new FeedbackInput(UUID.randomUUID().toString(),2L,n,Outcome.FAILED,
                FeedbackOrigin.SIMULATED_DEMO,"新增观察：原建议未能恢复。",null));admin();fails(409,()->approve(id,0,p.card()));
    }
    @Test void outboxFailureRollsBackCandidateStructureVersionAndReviewAudit()throws Exception {
        var p=prepare(ticket,card("仅限演示环境"));String id=candidate(p);
        doThrow(new IllegalStateException("test outbox unavailable")).when(outbox).enqueue(anyString(),anyString(),anyString(),anyLong(),anyString(),anyInt());
        assertThrows(IllegalStateException.class,()->approve(id,0,p.card()));
        assertTrue(repo.version(id,1).isEmpty());assertEquals(0,knowledge.candidate(id,false).revision());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_review_audit WHERE candidate_id=?",Integer.class,id));
    }
    @Test void indexedApprovedCardIsReusableUntilRetracted()throws Exception {
        var p=prepare(ticket,card("仅限演示环境"));String id=candidate(p);approve(id,0,p.card());assertNull(repo.reusable(p.card().id()));
        worker.process(message(id,"INDEX",1));assertEquals(id,repo.reusable(p.card().id()).candidateId());
        assertTrue(retrieval.searchByKeyword(p.card().question()).hits().stream().anyMatch(h->h.chunk().sourceFile().equals(new KnowledgeSource(id,1).file())));
        admin();review.retract(id,1L);assertNull(repo.reusable(p.card().id()));assertEquals("RETRACTED",repo.version(id,2).orElseThrow().state());
    }
    @Test void structuredRevisionRequiresRetractionPreservesHistoryAndCannotResurrectOldVersion()throws Exception {
        var p=prepare(ticket,card("原手机号可用"));String id=candidate(p);approve(id,0,p.card());worker.process(message(id,"INDEX",1));
        handler();var next=prepare(ticket,card("原手机号不可用"));admin();fails(400,()->cards.bind(id,new Binding(1L,next.submission().id())));
        review.retract(id,1L);worker.process(message(id,"DELETE",2));
        assertEquals(3,cards.bind(id,new Binding(2L,next.submission().id())).revision());approve(id,3,next.card());worker.process(message(id,"INDEX",4));
        assertEquals(p.card().hash(),repo.version(id,1).orElseThrow().cardHash());assertEquals(next.card().hash(),repo.version(id,4).orElseThrow().cardHash());
        worker.process(message(id,"INDEX",1));assertEquals(4L,knowledge.candidate(id,false).indexedRevision());
    }
    @Test void missingCurrentStructureVersionFailsClosed()throws Exception {
        var p=prepare(ticket,card("仅限演示环境"));String id=candidate(p);approve(id,0,p.card());
        db.update("DELETE FROM knowledge_experience_version WHERE candidate_id=? AND revision=1",id);
        assertThrows(IllegalStateException.class,()->review.retract(id,1L));assertEquals(1,knowledge.candidate(id,false).revision());
    }
    @Test void newFailureFeedbackHidesPublishedSourceWithoutWaitingForPgDeletion()throws Exception {
        var p=prepare(ticket,card("仅限演示环境"));String id=candidate(p);approve(id,0,p.card());worker.process(message(id,"INDEX",1));
        String source=new KnowledgeSource(id,1).file();
        assertTrue(knowledge.visibleSources(Set.of(id)).contains(source));
        handler();long n=repo.positives(p.submission().id()).get(0);
        notes.feedback(ticket,new FeedbackInput(UUID.randomUUID().toString(),2L,n,Outcome.FAILED,FeedbackOrigin.SIMULATED_DEMO,"新增反馈证明该正向依据失败。",null));
        assertNull(repo.reusable(p.card().id()));assertTrue(knowledge.visibleSources(Set.of(id)).isEmpty());
        assertTrue(vectors.publicationMatches(new KnowledgeSource(id,1),repo.version(id,1).orElseThrow().publicationHash(),"bge-m3:latest"));
    }
    @Test void reviewSeesBindingCommittedAfterItsInitialCandidateRead()throws Exception {
        var p=prepare(ticket,card("仅限演示环境"));handler();
        var dto=new TicketResolveDTO();dto.setOperatorId(220925L);dto.setSolution(p.card().answer());tickets.resolve(ticket,dto);
        String id=db.queryForObject("SELECT resolution_id FROM ticket_resolution WHERE ticket_id=?",String.class,ticket);
        resolutions.add(id);worker.process(message(id,"DRAFT",0));
        var read=new CountDownLatch(1);var bound=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        doAnswer(call->{Object initial=call.callRealMethod();read.countDown();assertTrue(bound.await(10,TimeUnit.SECONDS));return initial;})
                .when(knowledge).candidate(id,false);
        try {
            Future<Integer> result=pool.submit(()->{admin();try{
                return assertThrows(ApiFailure.class,()->review.approve(id,0L,p.card().question(),"旧客户端提交的任意审核文本，不符合最新经验结构。","SHARED_DEMO",true)).status();
            }finally{ActorContext.clear();}});
            assertTrue(read.await(10,TimeUnit.SECONDS));admin();cards.bind(id,new Binding(0L,p.submission().id()));bound.countDown();
            assertEquals(400,result.get(15,TimeUnit.SECONDS));assertTrue(repo.version(id,1).isEmpty());
        }finally{bound.countDown();pool.shutdownNow();}
    }
}
