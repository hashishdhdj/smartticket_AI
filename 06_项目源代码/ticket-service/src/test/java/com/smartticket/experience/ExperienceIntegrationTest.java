package com.smartticket.experience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.dto.*;
import com.smartticket.exception.ApiFailure;
import com.smartticket.security.ActorContext;
import com.smartticket.service.TicketService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.smartticket.experience.ExperienceContract.*;

@SpringBootTest(properties={
    "smartticket.experience.enabled=true","smartticket.knowledge.enabled=false",
    "smartticket.knowledge.async-enabled=false","smartticket.ai.enabled=false",
    "smartticket.vector.index-on-startup=false","smartticket.phase3.record-tasks=false",
    "smartticket.phase3.async-enabled=false","smartticket.phase3.security-enabled=true",
    "smartticket.phase3.users[0].id=110923","smartticket.phase3.users[0].role=SUBMITTER","smartticket.phase3.users[0].token=experience-submitter-key",
    "smartticket.phase3.users[1].id=220923","smartticket.phase3.users[1].role=HANDLER","smartticket.phase3.users[1].token=experience-handler-key",
    "smartticket.phase3.users[2].id=990923","smartticket.phase3.users[2].role=ADMIN","smartticket.phase3.users[2].token=experience-admin-key",
    "smartticket.phase3.users[3].id=220924","smartticket.phase3.users[3].role=HANDLER","smartticket.phase3.users[3].token=experience-other-handler-key"
})
@AutoConfigureMockMvc
class ExperienceIntegrationTest {
    @Autowired TicketService tickets;
    @Autowired ExperienceService service;
    @Autowired ExperienceProperties config;
    @Autowired com.smartticket.config.RuntimeProperties runtime;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    private final List<Long> ids=new ArrayList<>();
    private long ticket;
    private static final String PREFIX="/api/tickets/";
    private void actor(long id,String role){ActorContext.set(new ActorContext.Actor(id,role));}
    private void handler(){actor(220923,"HANDLER");}
    private void admin(){actor(990923,"ADMIN");}
    private long processing() {
        actor(110923,"SUBMITTER");var create=new TicketCreateDTO();
        create.setCreatorId(110923L);create.setTitle("G1合成一线记录测试");
        long id=tickets.create(create).getId();ids.add(id);
        admin();var assign=new TicketAssignDTO();assign.setAssigneeId(220923L);assign.setOperatorId(990923L);
        tickets.assign(id,assign);handler();return id;
    }
    @BeforeEach void setup(){config.setEnabled(true);ticket=processing();}
    @AfterEach void clean(){
        config.setEnabled(true);ActorContext.clear();
        for(long id:ids){
            db.update("DELETE FROM cache_invalidation WHERE ticket_id=?",id);
            db.update("DELETE FROM ticket_log WHERE ticket_id=?",id);
            db.update("DELETE FROM ticket WHERE id=?",id);
        }
    }
    private InteractionInput input(){return new InteractionInput(UUID.randomUUID().toString(),1L,Kind.STAFF_REPLY,
            "先检查连接配置，再重试并记录结果。",Origin.AI_SYNTHETIC_DEMO,null,List.of(new Evidence("docs/architecture/test.md","合成参考，不代表已核实")));}
    private FeedbackInput feedback(long note){return new FeedbackInput(UUID.randomUUID().toString(),1L,note,Outcome.UNVERIFIED,
            FeedbackOrigin.SIMULATED_DEMO,"合成反馈，尚未核实实际效果。",null);}
    private String body(Object value)throws Exception{return json.writeValueAsString(value);}
    private void fails(int expected,Runnable action){assertEquals(expected,assertThrows(ApiFailure.class,action::run).status());}
    private int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE ticket_id=?",Integer.class,ticket);}

    @Test void realHttpWritesTrustedActorAndSnapshotWithoutChangingBusinessOrPublishing() throws Exception {
        ActorContext.clear();var request=input();
        var response=mvc.perform(post(PREFIX+ticket+"/interactions").header("X-API-Key","experience-handler-key")
                .contentType("application/json").content(body(request))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorId").value(220923)).andExpect(jsonPath("$.data.authorRole").value("HANDLER"))
                .andExpect(jsonPath("$.data.ticketVersion").value(1)).andReturn();
        long note=json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asLong();
        assertEquals(request.content(),db.queryForObject("SELECT content FROM ticket_interaction WHERE id=?",String.class,note));
        assertNotNull(db.queryForObject("SELECT created_at FROM ticket_interaction WHERE id=?",java.sql.Timestamp.class,note));
        assertEquals(1L,db.queryForObject("SELECT version FROM ticket WHERE id=?",Long.class,ticket));
        assertEquals("PROCESSING",db.queryForObject("SELECT status FROM ticket WHERE id=?",String.class,ticket));
        assertEquals(0,count("ticket_resolution"));assertEquals(0,count("ai_analysis_task"));
    }
    @Test void unauthenticatedRequestRejected() throws Exception {
        ActorContext.clear();mvc.perform(get(PREFIX+ticket+"/interactions")).andExpect(status().isUnauthorized());
    }
    @Test void submitterCannotReadInternalRecordsEvenOwnTicket() throws Exception {
        service.append(ticket,input());ActorContext.clear();
        mvc.perform(get(PREFIX+ticket+"/interactions").header("X-API-Key","experience-submitter-key")).andExpect(status().isForbidden());
        mvc.perform(post(PREFIX+ticket+"/experience-feedback").header("X-API-Key","experience-submitter-key")
                .contentType("application/json").content(body(feedback(1)))).andExpect(status().isForbidden());
    }
    @Test void otherHandlerCannotReadOrWrite() {
        actor(220924,"HANDLER");fails(403,()->service.append(ticket,input()));
        fails(403,()->service.interactions(ticket,0,20));fails(403,()->service.feedback(ticket,0,20));
    }
    @Test void adminCanContributeAndReadWithoutBecomingAssignee() {
        admin();var note=service.append(ticket,input());assertEquals(990923,note.authorId());
        assertEquals("ADMIN",note.authorRole());assertEquals(1,service.interactions(ticket,0,20).records().size());
        assertEquals(220923L,db.queryForObject("SELECT assignee_id FROM ticket WHERE id=?",Long.class,ticket));
    }
    @Test void disabledCollectionReturns404AndPreservesRows() {
        service.append(ticket,input());config.setEnabled(false);
        fails(404,()->service.append(ticket,input()));fails(404,()->service.interactions(ticket,0,20));
        assertEquals(1,count("ticket_interaction"));
    }
    @Test void legacyUnauthenticatedModeStillCannotBypassTrustedActor() {
        ActorContext.clear();runtime.setSecurityEnabled(false);
        try {fails(401,()->service.append(ticket,input()));}
        finally {runtime.setSecurityEnabled(true);}
    }
    @Test void duplicateRequestReturnsSameRecordIncludingAfterTicketChanges() {
        var request=input();var first=service.append(ticket,request);
        db.update("UPDATE ticket SET version=version+1 WHERE id=?",ticket);
        assertEquals(first,service.append(ticket,request));assertEquals(1,count("ticket_interaction"));
        fails(409,()->service.append(ticket,input()));
    }
    @Test void duplicateKeyWithDifferentContentConflictsWithoutOverwrite() {
        var request=input();var original=service.append(ticket,request);
        fails(409,()->service.append(ticket,new InteractionInput(request.requestId(),1L,request.kind(),"内容已改变，不能覆盖原记录。",
                request.origin(),null,request.evidenceRefs())));
        assertEquals(original,service.interactions(ticket,0,20).records().get(0));
    }
    @Test void concurrentDuplicateRequestsProduceExactlyOneRow() throws Exception {
        var request=input();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Interaction> call=()->{handler();try{start.await();return service.append(ticket,request);}finally{ActorContext.clear();}};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();
            assertEquals(a.get(15,TimeUnit.SECONDS).id(),b.get(15,TimeUnit.SECONDS).id());
            assertEquals(1,count("ticket_interaction"));
        } finally {pool.shutdownNow();}
    }
    @Test void staleVersionDoesNotWrite() {
        var request=input();db.update("UPDATE ticket SET version=2 WHERE id=?",ticket);
        fails(409,()->service.append(ticket,request));assertEquals(0,count("ticket_interaction"));
    }
    @Test void reassignmentRevokesReadAndEvenIdempotentReplay() {
        var request=input();service.append(ticket,request);
        db.update("UPDATE ticket SET assignee_id=220924,version=version+1 WHERE id=?",ticket);
        fails(403,()->service.interactions(ticket,0,20));fails(403,()->service.append(ticket,request));
    }
    @Test void deletedTicketIsInvisible() {
        service.append(ticket,input());db.update("UPDATE ticket SET deleted=1 WHERE id=?",ticket);
        fails(404,()->service.interactions(ticket,0,20));fails(404,()->service.append(ticket,input()));
    }
    @Test void validFeedbackPersistsTrustedProvenanceAndIsIdempotent() {
        long note=service.append(ticket,input()).id();var request=feedback(note);
        var result=service.feedback(ticket,request);assertEquals(result,service.feedback(ticket,request));
        assertEquals(1,count("experience_feedback"));assertEquals(220923,result.authorId());
        assertEquals(FeedbackOrigin.SIMULATED_DEMO,result.origin());
        assertEquals(List.of(result),service.feedback(ticket,0,20).records());
        fails(409,()->service.feedback(ticket,new FeedbackInput(request.requestId(),1L,note,Outcome.EFFECTIVE,request.origin(),request.comment(),null)));
        db.update("UPDATE ticket SET version=version+1 WHERE id=?",ticket);
        assertEquals(result,service.feedback(ticket,request));
        fails(409,()->service.feedback(ticket,feedback(note)));
    }
    @Test void syntheticRecordCannotClaimActualBusinessObservation() {
        long note=service.append(ticket,input()).id();var f=feedback(note);
        fails(400,()->service.feedback(ticket,new FeedbackInput(f.requestId(),1L,note,Outcome.EFFECTIVE,FeedbackOrigin.STAFF_OBSERVATION,f.comment(),null)));
        assertEquals(0,count("experience_feedback"));
    }
    @Test void staffEnteredFeedbackRemainsReportedObservation() {
        var i=input();var note=service.append(ticket,new InteractionInput(i.requestId(),1L,i.kind(),i.content(),Origin.STAFF_ENTERED,null,i.evidenceRefs()));
        var f=feedback(note.id());var result=service.feedback(ticket,new FeedbackInput(f.requestId(),1L,note.id(),Outcome.EFFECTIVE,
                FeedbackOrigin.STAFF_OBSERVATION,"处理人报告重试后恢复，需要后续核实。",null));
        assertEquals(FeedbackOrigin.STAFF_OBSERVATION,result.origin());assertEquals(0,count("ticket_resolution"));
    }
    @Test void crossTicketFeedbackAndCorrectionAreRejected() {
        long other=processing();long foreign=service.append(other,input()).id();
        fails(404,()->service.feedback(ticket,feedback(foreign)));
        fails(404,()->service.append(ticket,new InteractionInput(UUID.randomUUID().toString(),1L,Kind.CORRECTION,
                "更正错误操作，不得跨工单关联。",Origin.AI_SYNTHETIC_DEMO,foreign,List.of())));
        assertEquals(0,count("ticket_interaction"));assertEquals(0,count("experience_feedback"));
    }
    @Test void correctionPreservesWrongAttemptAndLinksFeedback() {
        var original=service.append(ticket,input());
        var corrected=service.append(ticket,new InteractionInput(UUID.randomUUID().toString(),1L,Kind.CORRECTION,
                "更正：首次重试失败，应该先确认网络连通。",Origin.AI_SYNTHETIC_DEMO,original.id(),List.of()));
        var f=new FeedbackInput(UUID.randomUUID().toString(),1L,original.id(),Outcome.CORRECTED,
                FeedbackOrigin.SIMULATED_DEMO,"合成反馈：原建议已被更正。",corrected.id());
        assertEquals(corrected.id(),service.feedback(ticket,f).correctionInteractionId());
        assertEquals(List.of(original,corrected),service.interactions(ticket,0,20).records());
    }
    @Test void correctionMustReferToActualCorrectionOfSameOriginalAndOrigin() {
        var first=service.append(ticket,input());var other=service.append(ticket,input());
        fails(400,()->service.feedback(ticket,new FeedbackInput(UUID.randomUUID().toString(),1L,first.id(),Outcome.CORRECTED,
                FeedbackOrigin.SIMULATED_DEMO,"不能用普通回复冒充纠正记录。",other.id())));
        var correction=service.append(ticket,new InteractionInput(UUID.randomUUID().toString(),1L,Kind.CORRECTION,
                "更正另一条记录，不可错配原始回复。",Origin.AI_SYNTHETIC_DEMO,other.id(),List.of()));
        fails(400,()->service.feedback(ticket,new FeedbackInput(UUID.randomUUID().toString(),1L,first.id(),Outcome.CORRECTED,
                FeedbackOrigin.SIMULATED_DEMO,"不能用其他回复的纠正记录。",correction.id())));
        fails(400,()->service.append(ticket,new InteractionInput(UUID.randomUUID().toString(),1L,Kind.CORRECTION,
                "不可将合成纠正标为实际员工记录。",Origin.STAFF_ENTERED,first.id(),List.of())));
    }
    @Test void invalidCorrectionCombinationsDoNotWrite() {
        var request=input();fails(400,()->service.append(ticket,new InteractionInput(request.requestId(),1L,Kind.CORRECTION,
                request.content(),request.origin(),null,List.of())));
        long note=service.append(ticket,request).id();
        fails(400,()->service.append(ticket,new InteractionInput(UUID.randomUUID().toString(),1L,Kind.STAFF_REPLY,
                request.content(),request.origin(),note,List.of())));
        fails(400,()->service.feedback(ticket,new FeedbackInput(UUID.randomUUID().toString(),1L,note,Outcome.CORRECTED,
                FeedbackOrigin.SIMULATED_DEMO,"未填写对应的纠正记录。",null)));
        fails(400,()->service.feedback(ticket,new FeedbackInput(UUID.randomUUID().toString(),1L,note,Outcome.FAILED,
                FeedbackOrigin.SIMULATED_DEMO,"只有纠正反馈可以关联更正。",note)));
    }
    @Test void cursorPaginationIsBoundedAndDoesNotRepeat() {
        for(int i=0;i<3;i++)service.append(ticket,input());
        var first=service.interactions(ticket,0,2);assertEquals(2,first.records().size());assertNotNull(first.nextAfterId());
        var second=service.interactions(ticket,first.nextAfterId(),2);assertEquals(1,second.records().size());assertNull(second.nextAfterId());
        assertTrue(second.records().get(0).id()>first.nextAfterId());
        fails(400,()->service.interactions(ticket,0,101));fails(400,()->service.feedback(ticket,-1,20));
    }
    @Test void forgedIdentityUnknownFieldsAndMalformedInputFailAtHttpBoundary() throws Exception {
        ActorContext.clear();var valid=json.valueToTree(input());
        for(String field:List.of("authorId","authorRole","verified","createdAt","ticketVersion")) {
            var forged=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)forged).put(field,"ADMIN");
            mvc.perform(post(PREFIX+ticket+"/interactions").header("X-API-Key","experience-handler-key")
                    .contentType("application/json").content(body(forged))).andExpect(status().isBadRequest());
        }
        for(String field:List.of("requestId","content","origin","kind","expectedVersion","evidenceRefs")) {
            JsonNode invalid=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)invalid).putNull(field);
            mvc.perform(post(PREFIX+ticket+"/interactions").header("X-API-Key","experience-handler-key")
                    .contentType("application/json").content(body(invalid))).andExpect(status().isBadRequest());
        }
        assertEquals(0,count("ticket_interaction"));
    }
    @Test void invalidUuidWhitespaceAndOversizedInputRejected() throws Exception {
        var i=input();fails(400,()->service.append(ticket,new InteractionInput("not-a-uuid",1L,i.kind(),i.content(),i.origin(),null,List.of())));
        fails(400,()->service.append(ticket,new InteractionInput(i.requestId(),1L,i.kind(),"     ",i.origin(),null,List.of())));
        ActorContext.clear();var large=new InteractionInput(UUID.randomUUID().toString(),1L,i.kind(),"x".repeat(4001),i.origin(),null,List.of());
        mvc.perform(post(PREFIX+ticket+"/interactions").header("X-API-Key","experience-handler-key")
                .contentType("application/json").content(body(large))).andExpect(status().isBadRequest());
        assertEquals(0,count("ticket_interaction"));
    }
    @Test void noPublicEditOrDeleteRouteForAppendOnlyEvidence() throws Exception {
        ActorContext.clear();for(var method:List.of(put(PREFIX+ticket+"/interactions"),delete(PREFIX+ticket+"/interactions")))
            mvc.perform(method.header("X-API-Key","experience-handler-key")).andExpect(status().isMethodNotAllowed());
    }
    @Test void deletingOwnedFixtureCascadesCorrectionsAndFeedbackOnlyForThatTicket() {
        long other=processing();var untouched=service.append(other,input());
        var original=service.append(ticket,input());
        var correction=service.append(ticket,new InteractionInput(UUID.randomUUID().toString(),1L,Kind.CORRECTION,
                "更正内容保留完整历史，不影响他人工单。",Origin.AI_SYNTHETIC_DEMO,original.id(),List.of()));
        service.feedback(ticket,new FeedbackInput(UUID.randomUUID().toString(),1L,original.id(),Outcome.CORRECTED,
                FeedbackOrigin.SIMULATED_DEMO,"测试清理只删除本次工单。",correction.id()));
        db.update("DELETE FROM ticket WHERE id=?",ticket);
        assertEquals(0,count("ticket_interaction"));assertEquals(0,count("experience_feedback"));
        assertEquals(untouched,service.interactions(other,0,20).records().get(0));
    }
}
