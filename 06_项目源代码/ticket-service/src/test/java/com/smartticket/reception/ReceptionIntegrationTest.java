package com.smartticket.reception;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.agent.*;
import com.smartticket.config.RuntimeProperties;
import com.smartticket.dto.*;
import com.smartticket.exception.ApiFailure;
import com.smartticket.rag.*;
import com.smartticket.security.ActorContext;
import com.smartticket.service.TicketService;
import com.smartticket.vo.TicketVO;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL + HTTP boundaries + real Agent loop, deterministic model/retrieval fixtures, zero vendor calls. */
@SpringBootTest(properties={
    "smartticket.phase3.security-enabled=true",
    "smartticket.reception.enabled=true","smartticket.reception.async-enabled=false",
    "smartticket.phase3.async-enabled=false","smartticket.ai.enabled=false",
    "smartticket.phase3.users[0].id=700100","smartticket.phase3.users[0].role=SUBMITTER",
    "smartticket.phase3.users[0].token=reception-customer",
    "smartticket.phase3.users[1].id=700200","smartticket.phase3.users[1].role=HANDLER",
    "smartticket.phase3.users[1].token=reception-handler",
    "smartticket.phase3.users[2].id=700900","smartticket.phase3.users[2].role=ADMIN",
    "smartticket.phase3.users[2].token=reception-admin"
})
@AutoConfigureMockMvc
class ReceptionIntegrationTest {
    @Autowired TicketService tickets;
    @Autowired ReceptionRepository repo;
    @Autowired ReceptionWorker worker;
    @Autowired ReceptionProperties config;
    @Autowired RuntimeProperties runtime;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager tm;
    @MockBean AgentModel model;
    @MockBean KnowledgeSearchService knowledge;
    private final List<Long> ids=new ArrayList<>();

    void customer(){ActorContext.set(new ActorContext.Actor(700100,"SUBMITTER"));}
    void admin(){ActorContext.set(new ActorContext.Actor(700900,"ADMIN"));}
    TicketVO create(){var dto=new TicketCreateDTO();dto.setTitle("如何重置演示账户密码");dto.setDescription("原手机能收到短信");
        var t=tickets.create(dto);ids.add(t.getId());return t;}
    String state(long id){return db.queryForObject("SELECT state FROM ticket_reception WHERE ticket_id=?",String.class,id);}
    int turn(long id){return db.queryForObject("SELECT turn_no FROM ticket_reception WHERE ticket_id=?",Integer.class,id);}
    AgentModel.Reply text(String answer,String next) throws Exception {
        var handoff=switch(next) {
            case "ASK" -> Map.of("state","NEEDS_CLARIFICATION","reason","USER_CONTEXT_REQUIRED","detail","请提供原手机号是否可用");
            case "HUMAN" -> Map.of("state","HUMAN_ACTION_REQUIRED","reason","HUMAN_OPERATION_REQUIRED","detail","用户要求实际退款，本系统没有退款执行工具");
            default -> Map.of("state","ANSWERED","reason","NONE","detail","");
        };
        return new AgentModel.Reply(new AssistantMessage(json.writeValueAsString(Map.of("answer",answer,
                "needHuman",next.equals("HUMAN"),"sourceIds",next.equals("ANSWER")?List.of("faq/reception.md#0"):List.of(),"handoff",handoff))),20);
    }
    AgentModel.Reply call(String name,String args){return new AgentModel.Reply(AssistantMessage.builder()
            .content("").toolCalls(List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,args))).build(),20);}
    void answer() throws Exception {
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(call("searchKnowledge","{\"query\":\"重置密码\"}"),
                text("演示规则：在登录页选择忘记密码，验证原手机号后设置新密码。","ANSWER"));
    }

    @BeforeEach void before(){customer();config.setEnabled(true);config.setMaxTurns(6);config.setMaxAttempts(3);
        runtime.setDailyTokens(200000);runtime.setDailyRequests(200);runtime.setMaxRunTokens(12000);
        reset(model,knowledge);
        when(knowledge.search(anyString())).thenReturn(new KnowledgeSearchResult(List.of(new FusedHit(
                new KnowledgeChunk("faq/reception.md","合成演示","重置密码",0,"演示规则：原手机可用时，通过短信验证重置密码。"),
                .03,Set.of(RetrievalSource.VECTOR))),List.of(),1));}
    @AfterEach void after(){ActorContext.clear();config.setEnabled(true);
        for(long id:ids){db.update("DELETE FROM ticket_reception_message WHERE ticket_id=?",id);db.update("DELETE FROM ticket_reception WHERE ticket_id=?",id);
            db.update("DELETE FROM cache_invalidation WHERE ticket_id=?",id);db.update("DELETE FROM ticket_log WHERE ticket_id=?",id);
            db.update("DELETE FROM ticket WHERE id=?",id);}
        db.update("DELETE FROM agent_step WHERE run_id IN (SELECT run_id FROM agent_run WHERE actor_id=700100 OR actor_id=700900)");
        db.update("DELETE FROM agent_run WHERE actor_id=700100 OR actor_id=700900");
        db.update("DELETE FROM ai_usage_budget WHERE actor_id=700100 OR actor_id=700900");}

    @Test void creationDurablyQueuesAgentAndAvoidsDuplicateClassification(){var t=create();
        assertEquals("PROCESSING",t.getStatus());assertEquals(1L,t.getVersion());assertEquals("QUEUED",state(t.getId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ai_analysis_task WHERE ticket_id=?",Integer.class,t.getId()));
        verifyNoInteractions(model);}

    @Test void enrollmentRollsBackWithTicketTransaction(){var tx=new TransactionTemplate(tm);
        tx.executeWithoutResult(s->{create();s.setRollbackOnly();});
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ticket WHERE id=?",Integer.class,ids.get(0)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ticket_reception WHERE ticket_id=?",Integer.class,ids.get(0)));}

    @Test void actualAgentToolsAnswerThenCustomerConfirmsResolved() throws Exception {var t=create();answer();
        assertTrue(worker.runOnce());assertEquals("WAITING_CUSTOMER",state(t.getId()));
        assertEquals("PENDING_FEEDBACK",tickets.getById(t.getId()).getStatus());
        verify(model,times(2)).call(anyList(),anyList(),anyInt());
        repo.respond(t.getId(),"confirmed",1,"RESOLVED","");
        assertEquals("RESOLVED",state(t.getId()));assertEquals("RESOLVED",tickets.getById(t.getId()).getStatus());}

    @Test void missingUserInformationWaitsWithoutHumanThenContinues() throws Exception {var t=create();
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(text("原手机号是否还能接收短信？","ASK"));
        worker.runOnce();assertEquals("WAITING_INPUT",state(t.getId()));assertEquals("PROCESSING",tickets.getById(t.getId()).getStatus());
        repo.respond(t.getId(),"reply1",1,"MESSAGE","可以接收短信");assertEquals(2,turn(t.getId()));
        answer();worker.runOnce();assertEquals("WAITING_CUSTOMER",state(t.getId()));
        var captor=org.mockito.ArgumentCaptor.forClass(List.class);verify(model,atLeastOnce()).call(captor.capture(),anyList(),anyInt());
        assertTrue(captor.getAllValues().toString().contains("可以接收短信"));}

    @Test void unsupportedActualOperationProducesHumanQueueWithContext() throws Exception {var t=create();
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(text("需要实际退款操作，请由人工处理。","HUMAN"));
        worker.runOnce();assertEquals("HUMAN_REQUIRED",state(t.getId()));
        assertEquals("PENDING_ASSIGN",tickets.getById(t.getId()).getStatus());
        admin();assertTrue(json.writeValueAsString(repo.view(t.getId())).contains("需要实际退款操作"));}

    @Test void modelOutageDoesNotPreventCreationAndFallsBackToHuman() {var t=create();
        when(model.call(anyList(),anyList(),anyInt())).thenThrow(new ApiFailure(503,10006,"模型未配置"));
        worker.runOnce();assertEquals("HUMAN_REQUIRED",state(t.getId()));assertEquals("PENDING_ASSIGN",tickets.getById(t.getId()).getStatus());}

    @Test void manualAssignmentFencesInFlightResultAndDoesNotOverwriteHandler(){var t=create();var job=repo.claim().orElseThrow();
        admin();var dto=new TicketAssignDTO();dto.setAssigneeId(700200L);tickets.assign(t.getId(),dto);
        assertFalse(repo.finish(job,new AgentService.Run("stale","SUCCEEDED","旧答复",false,1,0,1,List.of(),List.of(),"VALIDATED_OUTPUT","ANSWER","NONE",null)));
        assertEquals("HUMAN_ACTIVE",state(t.getId()));assertEquals(700200L,tickets.getById(t.getId()).getAssigneeId());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ticket_reception_message WHERE ticket_id=?",Integer.class,t.getId()));}

    @Test void customerCanRequestHumanWhileAgentRunsAndOldAnswerIsDiscarded(){var t=create();var job=repo.claim().orElseThrow();
        repo.respond(t.getId(),"human",1,"HUMAN","");repo.failed(job);
        assertEquals("HUMAN_REQUIRED",state(t.getId()));assertEquals("PENDING_ASSIGN",tickets.getById(t.getId()).getStatus());}

    @Test void customerHumanRequestAfterAnswerRespectsStateMachine() throws Exception {var t=create();answer();worker.runOnce();
        repo.respond(t.getId(),"human-after-answer",1,"HUMAN","");
        assertEquals("PENDING_ASSIGN",tickets.getById(t.getId()).getStatus());assertEquals("HUMAN_REQUIRED",state(t.getId()));}

    @Test void duplicateCustomerReplyIsIdempotentAndChangedPayloadConflicts() throws Exception {var t=create();
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(text("手机号可用吗？","ASK"));worker.runOnce();
        repo.respond(t.getId(),"reply-key",1,"MESSAGE","可用");repo.respond(t.getId(),"reply-key",1,"MESSAGE","可用");
        assertEquals(2,turn(t.getId()));
        assertEquals(409,assertThrows(ApiFailure.class,()->repo.respond(t.getId(),"reply-key",1,"MESSAGE","不可用")).status());}

    @Test void staleTurnAndPrematureConfirmationAreRejected(){var t=create();
        assertEquals(409,assertThrows(ApiFailure.class,()->repo.respond(t.getId(),"stale",2,"HUMAN","")).status());
        assertEquals(409,assertThrows(ApiFailure.class,()->repo.respond(t.getId(),"premature",1,"RESOLVED","")).status());}

    @Test void expiredLeaseCanBeReclaimedButOldTokenCannotCommit(){var t=create();var old=repo.claim().orElseThrow();
        assertTrue(repo.claim().isEmpty());
        db.update("UPDATE ticket_reception SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE ticket_id=?",t.getId());
        var next=repo.claim().orElseThrow();assertNotEquals(old.token(),next.token());repo.failed(old);assertEquals("RUNNING",state(t.getId()));
        repo.failed(next);assertEquals("HUMAN_REQUIRED",state(t.getId()));}

    @Test void exhaustedCrashRecoveryHandsOffWithoutAnotherModelCall(){var t=create();repo.claim();
        db.update("UPDATE ticket_reception SET attempts=3,lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE ticket_id=?",t.getId());
        assertTrue(repo.claim().isEmpty());assertEquals("HUMAN_REQUIRED",state(t.getId()));verifyNoInteractions(model);}

    @Test void repeatedClarificationHasBoundedTurns() throws Exception {var t=create();config.setMaxTurns(1);
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(text("需要更多信息？","ASK"));worker.runOnce();
        assertEquals("HUMAN_REQUIRED",state(t.getId()));}

    @Test void adminCreatorStillCannotExposeAssignmentTool() throws Exception {admin();var t=create();
        when(model.call(anyList(),anyList(),anyInt())).thenAnswer(inv->{
            List<org.springframework.ai.tool.ToolCallback> defs=inv.getArgument(1);
            assertTrue(defs.stream().noneMatch(d->Set.of("prepareAssignTicket","getTicketLogs").contains(d.getToolDefinition().name())));
            assertEquals("SUBMITTER",ActorContext.require().role());return text("原手机号可用吗？","ASK");});
        worker.runOnce();assertEquals("WAITING_INPUT",state(t.getId()));assertTrue(ActorContext.require().admin());}

    @Test void requestBudgetFailureRoutesToHumanWithoutCallingModel(){var t=create();runtime.setDailyRequests(0);
        worker.runOnce();assertEquals("HUMAN_REQUIRED",state(t.getId()));verifyNoInteractions(model);}

    @Test void manualInputEditFencesResultAndReturnsToHumanQueue(){var t=create();var job=repo.claim().orElseThrow();
        var dto=new TicketUpdateDTO();dto.setTitle("问题已经变化");dto.setPriority("MEDIUM");tickets.update(t.getId(),dto);
        repo.failed(job);assertEquals("HUMAN_REQUIRED",state(t.getId()));assertEquals("PENDING_ASSIGN",tickets.getById(t.getId()).getStatus());}

    @Test void httpPermissionsValidationAndNoStaffDetailsForCustomer() throws Exception {var t=create();answer();worker.runOnce();
        mvc.perform(get("/api/tickets/"+t.getId()+"/reception")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tickets/"+t.getId()+"/reception").header("X-API-Key","reception-handler")).andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets/"+t.getId()+"/reception").header("X-API-Key","reception-customer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.receptionState").value("WAITING_CUSTOMER"))
                .andExpect(jsonPath("$.data.lastRunId").doesNotExist()).andExpect(jsonPath("$.data.steps").doesNotExist());
        mvc.perform(post("/api/tickets/"+t.getId()+"/reception/responses").header("X-API-Key","reception-customer")
                .contentType("application/json").content("{\"requestId\":\"bad\",\"expectedTurn\":1,\"action\":\"CLOSE\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tickets/"+t.getId()+"/reception/responses").header("X-API-Key","reception-admin")
                .contentType("application/json").content("{\"requestId\":\"fake\",\"expectedTurn\":1,\"action\":\"RESOLVED\",\"message\":\"\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/"+t.getId()+"/reception/responses").header("X-API-Key","reception-customer")
                .contentType("application/json").content("{\"requestId\":\"ok\",\"expectedTurn\":1,\"action\":\"RESOLVED\",\"message\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.receptionState").value("RESOLVED"));}

    @Test void customerCannotReadOtherCustomerReception(){var t=create();ActorContext.set(new ActorContext.Actor(700101,"SUBMITTER"));
        assertEquals(403,assertThrows(ApiFailure.class,()->repo.view(t.getId())).status());}

    @Test void modelCannotReadOtherTicketOrInvokeStaffTools() {var t=create();
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(call("getTicketDetail","{\"ticketId\":999999}"),
                new AgentModel.Reply(new AssistantMessage("无效输出"),20));
        worker.runOnce();assertEquals("HUMAN_REQUIRED",state(t.getId()));}

    @Test void concurrentClaimHasOnlyOneOwner() throws Exception {create();
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Optional<ReceptionRepository.Job>> claim=()->{start.await();return repo.claim();};
            var a=pool.submit(claim);var b=pool.submit(claim);start.countDown();
            assertEquals(1,(a.get(5,TimeUnit.SECONDS).isPresent()?1:0)+(b.get(5,TimeUnit.SECONDS).isPresent()?1:0));
        } finally {pool.shutdownNow();}
    }

    @Test void concurrentSameCustomerReplyAdvancesOnlyOneTurn() throws Exception {var t=create();
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(text("手机号可用吗？","ASK"));worker.runOnce();
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Object> send=()->{customer();try {start.await();return repo.respond(t.getId(),"same-key",1,"MESSAGE","可用");}
                finally{ActorContext.clear();}};
            var a=pool.submit(send);var b=pool.submit(send);start.countDown();a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
            assertEquals(2,turn(t.getId()));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ticket_reception_message WHERE ticket_id=? AND kind='CUSTOMER'",Integer.class,t.getId()));
        } finally {pool.shutdownNow();}
    }

    @Test void customerCannotContinueAfterHumanTakesOver(){var t=create();admin();var dto=new TicketAssignDTO();dto.setAssigneeId(700200L);
        tickets.assign(t.getId(),dto);customer();
        assertEquals(409,assertThrows(ApiFailure.class,()->repo.respond(t.getId(),"late",1,"MESSAGE","继续自动处理")).status());}

    @Test void deletedTicketCannotBeReadOrResumed(){var t=create();db.update("UPDATE ticket SET deleted=1 WHERE id=?",t.getId());
        assertEquals(404,assertThrows(ApiFailure.class,()->repo.view(t.getId())).status());
        assertEquals(404,assertThrows(ApiFailure.class,()->repo.respond(t.getId(),"deleted",1,"HUMAN","")).status());
        assertTrue(repo.claim().isEmpty());assertEquals("CANCELLED",state(t.getId()));}

    @Test void answeredWithoutAnyToolEvidenceCannotBePublished() {var t=create();
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(new AgentModel.Reply(new AssistantMessage(
                "{\"answer\":\"已为你办理退款\",\"needHuman\":false,\"sourceIds\":[],\"handoff\":{\"state\":\"ANSWERED\",\"reason\":\"NONE\",\"detail\":\"\"}}"),20));
        worker.runOnce();assertEquals("HUMAN_REQUIRED",state(t.getId()));
        assertFalse(db.queryForList("SELECT content FROM ticket_reception_message WHERE ticket_id=?",t.getId()).toString().contains("已为你办理退款"));}

    @Test void waitingCustomerCanRejectAnswerAndResumeWithoutAdministrator() throws Exception {var t=create();answer();worker.runOnce();
        repo.respond(t.getId(),"not-solved",1,"MESSAGE","仍然无法重置，请核实后继续排查");
        assertEquals("QUEUED",state(t.getId()));assertEquals("PROCESSING",tickets.getById(t.getId()).getStatus());
        assertEquals(2,turn(t.getId()));}

    @Test void schedulerRunsOutsideDatabaseTransactionAndRestoresContext() throws Exception {var t=create();
        when(model.call(anyList(),anyList(),anyInt())).thenAnswer(inv->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return text("原手机号是否可用？","ASK");});
        config.setAsyncEnabled(true);
        try {worker.tick();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!state(t.getId()).equals("WAITING_INPUT") && System.nanoTime()<deadline)Thread.sleep(30);
            assertEquals("WAITING_INPUT",state(t.getId()));assertEquals(700100,ActorContext.require().id());
        } finally {config.setAsyncEnabled(false);}
    }

    @Test void customerKeyCannotCollideWithNextAgentMessage() throws Exception {var t=create();
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(text("原手机号是否可用？","ASK"));worker.runOnce();
        repo.respond(t.getId(),"agent-2",1,"MESSAGE","可以");answer();worker.runOnce();
        assertEquals("WAITING_CUSTOMER",state(t.getId()));
    }

    @Test void fullLengthIdempotencyKeyFitsNamespacedStorage() throws Exception {var t=create();
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(text("原手机号是否可用？","ASK"));worker.runOnce();
        repo.respond(t.getId(),"k".repeat(64),1,"MESSAGE","原手机号可用");
        assertEquals("QUEUED",state(t.getId()));
    }

    @Test void longInputAndHistoryLeaveRoomForAtLeastOneModelCall() throws Exception {
        var dto=new TicketCreateDTO();dto.setTitle("长描述演示工单");dto.setDescription("需要核实的情况。".repeat(400));
        long id=tickets.create(dto).getId();ids.add(id);
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(text("请补充相关情况。".repeat(100),"ASK"));worker.runOnce();
        assertEquals("WAITING_INPUT",state(id));
        repo.respond(id,"long-reply",1,"MESSAGE","客户补充信息".repeat(100));
        worker.runOnce();assertEquals("WAITING_INPUT",state(id));verify(model,times(2)).call(anyList(),anyList(),anyInt());
    }
}
