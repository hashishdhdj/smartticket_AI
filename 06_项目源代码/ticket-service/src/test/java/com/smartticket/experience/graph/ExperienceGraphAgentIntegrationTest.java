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
    "smartticket.experience.graph-search-enabled=true","smartticket.experience.enabled=true","smartticket.experience.cards-enabled=true","smartticket.experience.graph-index-enabled=true",
    "smartticket.knowledge.enabled=true","smartticket.knowledge.async-enabled=false",
    "smartticket.ai.enabled=false","smartticket.vector.index-on-startup=false","smartticket.vector.embedding.base-url=http://127.0.0.1:11434",
    "smartticket.phase3.record-tasks=false","smartticket.phase3.async-enabled=false","smartticket.phase3.security-enabled=true",
    "smartticket.phase3.users[0].id=111024","smartticket.phase3.users[0].role=SUBMITTER","smartticket.phase3.users[0].token=g4-submitter-key",
    "smartticket.phase3.users[1].id=221024","smartticket.phase3.users[1].role=HANDLER","smartticket.phase3.users[1].token=g4-handler-key",
    "smartticket.phase3.users[2].id=991024","smartticket.phase3.users[2].role=ADMIN","smartticket.phase3.users[2].token=g4-admin-key"
})
@AutoConfigureMockMvc
class ExperienceGraphAgentIntegrationTest {
    @Autowired com.smartticket.agent.AgentService agent;
    @Autowired com.smartticket.agent.AgentTools tools;
    @Autowired ExperienceGraphSearchService search;
    @SpyBean ExperienceGraphReader reader;
    @MockBean KnowledgeSearchService retrieval;
    @MockBean com.smartticket.agent.AgentModel model;
    @Autowired TicketService tickets;
    @Autowired ExperienceService notes;
    @Autowired ExperienceCardService cards;
    @Autowired ExperienceCardRepository cardRepo;
    @Autowired ExperienceProperties config;
    @Autowired com.smartticket.config.RuntimeProperties runtimeConfig;
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
    private static final Evidence REF=new Evidence("docs/api/experience-cards.md","G4合成验证来源");
    private void actor(long id,String role){ActorContext.set(new ActorContext.Actor(id,role));}
    private void handler(){actor(221024,"HANDLER");}
    private void admin(){actor(991024,"ADMIN");}
    private JdbcTemplate pg(){return (JdbcTemplate)ReflectionTestUtils.getField(vectors,"jdbcTemplate");}
    private static float[] vector(){float[] v=new float[1024];Arrays.fill(v,1f/32f);return v;}
    @BeforeEach void setup(){
        actor(111024,"SUBMITTER");
        config.setGraphIndexEnabled(true);config.setGraphSearchEnabled(true);condition="合成适用条件-"+UUID.randomUUID();
        when(embeddings.modelName()).thenReturn("bge-m3:latest");when(embeddings.isAvailable()).thenReturn(true);
        when(embeddings.embed(anyList())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return EmbeddingResult.ok(List.of(vector()),1,"bge-m3:latest");});
    }
    @AfterEach void clean(){
        reset(store,receipts,reader);config.setGraphIndexEnabled(true);config.setGraphSearchEnabled(true);ActorContext.clear();
        for(long actor:List.of(111024L,221024L,991024L)) {
            db.update("DELETE FROM agent_step WHERE run_id IN(SELECT run_id FROM agent_run WHERE actor_id=?)",actor);
            db.update("DELETE FROM agent_run WHERE actor_id=?",actor);db.update("DELETE FROM ai_usage_budget WHERE actor_id=?",actor);
        }
        for(String id:candidates){vectors.deleteBySourcePrefix(new KnowledgeSource(id,1).prefix());pg().update("DELETE FROM knowledge_publication WHERE candidate_id=CAST(? AS uuid)",id);}
        for(String id:edgesOwned)pg().update("DELETE FROM experience_edge WHERE edge_id=? AND NOT EXISTS(SELECT 1 FROM experience_edge_evidence WHERE edge_id=?)",id,id);
        for(String id:nodesOwned)pg().update("DELETE FROM experience_node WHERE node_id=? AND NOT EXISTS(SELECT 1 FROM experience_edge WHERE from_node=? OR to_node=?)",id,id,id);
        for(long id:ticketsOwned){cardIds.addAll(db.queryForList("SELECT card_id FROM experience_submission WHERE ticket_id=?",String.class,id));
            db.update("DELETE FROM cache_invalidation WHERE ticket_id=?",id);db.update("DELETE FROM ticket_log WHERE ticket_id=?",id);db.update("DELETE FROM ticket WHERE id=?",id);}
        for(String id:cardIds)db.update("DELETE FROM experience_card WHERE card_id=? AND NOT EXISTS(SELECT 1 FROM experience_submission WHERE card_id=?)"
                +" AND NOT EXISTS(SELECT 1 FROM knowledge_experience_version WHERE card_id=?)",id,id,id);
    }
    private long processing(){
        actor(111024,"SUBMITTER");var create=new TicketCreateDTO();create.setTitle("G4合成异常处理演示");create.setCreatorId(111024L);
        long id=tickets.create(create).getId();ticketsOwned.add(id);admin();var assign=new TicketAssignDTO();assign.setAssigneeId(221024L);assign.setOperatorId(991024L);
        tickets.assign(id,assign);handler();return id;
    }
    private Prepared prepare(long ticket,String condition){
        handler();long version=db.queryForObject("SELECT version FROM ticket WHERE id=?",Long.class,ticket);
        var n=notes.append(ticket,new InteractionInput(UUID.randomUUID().toString(),version,ExperienceContract.Kind.STAFF_REPLY,
                "合成员工排查：核对配置，再重试并记录结果。",Origin.AI_SYNTHETIC_DEMO,null,List.of(REF)));
        var c=new Card("G4合成账号异常排查",List.of("连接中断"),List.of(condition),List.of("核对连接配置","重试并记录结果"),
                List.of("未核实前不保证恢复"),List.of("错误做法：不核实配置就声称成功"),List.of("可能是配置问题，尚未证实"),List.of(REF),FactScope.PROJECT_RULE,SupportStatus.REFERENCED_DEMO);
        var p=cards.prepare(ticket,new Prepare(UUID.randomUUID().toString(),version,List.of(n.id()),List.of(),c));cardIds.add(p.card().id());return p;
    }
    private KnowledgeJobMessage message(String id,String kind,long revision)throws Exception {
        String payload=db.queryForObject("SELECT o.payload FROM knowledge_outbox o JOIN knowledge_job j ON j.job_id=o.job_id WHERE j.resolution_id=? AND j.kind=? AND j.revision=?"
                +" ORDER BY o.dispatch_sequence DESC LIMIT 1",String.class,id,kind,revision);return json.readValue(payload,KnowledgeJobMessage.class);
    }
    private String approved(Prepared p)throws Exception {
        handler();var dto=new TicketResolveDTO();dto.setOperatorId(221024L);dto.setSolution(p.card().answer());tickets.resolve(p.submission().ticketId(),dto);
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

    private void seed(String... ids) {
        var hits=Arrays.stream(ids).map(id->{var c=knowledge.candidate(id,false);var k=new KnowledgeSource(id,c.revision());
            return new FusedHit(new KnowledgeChunk(k.file(),"合成经验",c.question(),0,KnowledgeSource.content(c.question(),c.answer())),0.02,Set.of(RetrievalSource.KEYWORD));}).toList();
        when(retrieval.search(anyString())).thenReturn(new KnowledgeSearchResult(hits,List.of(),0));
    }
    private List<?> found(){return (List<?>)search.search("合成账号异常排查").get("sources");}
    private String sourceId(String id){return new KnowledgeSource(id,knowledge.candidate(id,false).revision()).file()+"#0";}
    private com.smartticket.agent.AgentModel.Reply tool(String name,String args) {
        return new com.smartticket.agent.AgentModel.Reply(org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
                .toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,args))).build(),50);
    }
    private com.smartticket.agent.AgentModel.Reply answer(List<String> ids,boolean human) throws Exception {
        return new com.smartticket.agent.AgentModel.Reply(new org.springframework.ai.chat.messages.AssistantMessage(json.writeValueAsString(
                Map.of("answer","合成演示：先核对适用条件，再核对配置并记录结果；不保证恢复。","needHuman",human,"sourceIds",ids))),50);
    }
    private void modelChain(String id,java.util.function.Consumer<List<org.springframework.ai.chat.messages.Message>> check) {
        var index=new java.util.concurrent.atomic.AtomicInteger();
        String expectedSource=id==null?null:sourceId(id);
        when(model.call(anyList(),anyList(),anyInt())).thenAnswer(call->{
            List<org.springframework.ai.chat.messages.Message> messages=call.getArgument(0);
            if(index.getAndIncrement()==0)return tool("searchExperienceGraph","{\"query\":\"合成账号异常排查\"}");
            check.accept(messages);return answer(expectedSource==null?List.of():List.of(expectedSource),false);
        });
    }

    @Test void ordinaryUserReadsOnlyReviewedSharedGraphNotEmployeeRecords() throws Exception {
        String id=published();seed(id);actor(111024,"SUBMITTER");var rows=found();assertEquals(1,rows.size());
        String value=json.writeValueAsString(rows);assertFalse(value.contains("authorId"));assertFalse(value.contains("ticketId"));assertFalse(value.contains("receiptHistory"));assertFalse(value.contains("submissionId"));
        assertTrue(value.contains("SHARED_DEMO"));assertTrue(value.contains("未核实前不保证恢复"));
    }
    @Test void sourceNotIndexedYetCannotBeReadEvenIfSeeded()throws Exception {
        String id=approved(prepare(processing(),condition));seed(id);actor(111024,"SUBMITTER");assertTrue(found().isEmpty());
    }
    @Test void immediateRetractionHidesGraphWhilePgEvidenceStillExists()throws Exception {
        String id=published();seed(id);review.retract(id,1L);assertTrue(evidence(id)>0);actor(111024,"SUBMITTER");assertTrue(found().isEmpty());
    }
    @Test void newFailureFeedbackHidesOtherwiseIntactGraph()throws Exception {
        String id=published();seed(id);var v=cardRepo.version(id,1).orElseThrow();var s=cardRepo.submission(v.submissionId());
        handler();notes.feedback(s.ticketId(),new FeedbackInput(UUID.randomUUID().toString(),2L,cardRepo.positives(s.id()).get(0),Outcome.FAILED,FeedbackOrigin.SIMULATED_DEMO,"再次出现异常",null));
        actor(111024,"SUBMITTER");assertTrue(found().isEmpty());
    }
    @Test void missingMysqlReceiptSuppressesFacts()throws Exception {
        String id=published();seed(id);db.update("DELETE FROM knowledge_graph_receipt WHERE candidate_id=?",id);assertTrue(found().isEmpty());
    }
    @Test void alteredPgNodeCannotBeUsedAsEvidence()throws Exception {
        String id=published();seed(id);var node=plan(id,1).nodes().stream().filter(n->n.key().equals(condition)).findFirst().orElseThrow();
        pg().update("UPDATE experience_node SET label='篡改指令' WHERE node_id=?",node.id());
        try{assertTrue(found().isEmpty());}finally{pg().update("UPDATE experience_node SET label=? WHERE node_id=?",node.label(),node.id());}
    }
    @Test void wrongEmbeddingModelIsNotReadyGraph()throws Exception {
        String id=published();seed(id);when(embeddings.modelName()).thenReturn("different-space");assertTrue(found().isEmpty());
    }
    @Test void graphFeatureOffLeavesOrdinaryKnowledgeToolAvailable()throws Exception {
        String id=published();seed(id);config.setGraphSearchEnabled(false);assertFalse(tools.definitions().stream().anyMatch(t->t.getToolDefinition().name().equals("searchExperienceGraph")));
        fails(404,()->search.search("问题"));assertFalse(((List<?>)((Map<?,?>)tools.execute("searchKnowledge","{\"query\":\"配置\"}","run")).get("sources")).isEmpty());
    }
    @Test void revisionAndGraphHashAreRecheckedBeforeCiting()throws Exception {
        String id=published();seed(id);String source=sourceId(id),hash=plan(id,1).graphHash();assertTrue(search.current(source,hash));assertFalse(search.current(source,"wrong"));
        review.retract(id,1L);assertFalse(search.current(source,hash));
    }
    @Test void realAgentLoopReceivesGraphEvidenceAndReturnsValidSource()throws Exception {
        String id=published();seed(id);modelChain(id,messages->{String context=messages.toString();assertTrue(context.contains("contextPaths"));assertTrue(context.contains(condition));
            assertTrue(context.contains("counterexamples"));assertTrue(context.contains("未证实"));assertTrue(context.contains("业务效果待核实"));});
        actor(111024,"SUBMITTER");var run=agent.run("按合成经验查询排查建议");assertEquals("SUCCEEDED",run.status());assertEquals(2,run.modelCalls());assertEquals(1,run.toolCalls());
        assertTrue(run.answer().contains(sourceId(id)));assertTrue(run.answer().contains("效果未独立验证"));assertEquals("searchExperienceGraph",run.steps().get(0).tool());
    }
    @Test void graphNodeIdCannotMasqueradeAsCitation()throws Exception {
        String id=published();seed(id);var index=new java.util.concurrent.atomic.AtomicInteger();String node=plan(id,1).nodes().get(0).id();
        when(model.call(anyList(),anyList(),anyInt())).thenAnswer(c->index.getAndIncrement()==0?tool("searchExperienceGraph","{\"query\":\"配置\"}"):answer(List.of(node),false));
        var run=agent.run("查经验");assertEquals("PARTIAL",run.status());assertEquals("UNSUPPORTED_SOURCE",run.statusReason());assertFalse(run.answer().contains(node));
    }
    @Test void emptyGraphSearchStillCountsAsKnowledgeSearchAndRequiresHuman()throws Exception {
        when(retrieval.search(anyString())).thenReturn(new KnowledgeSearchResult(List.of(),List.of(),0));modelChain(null,m->{});
        var run=agent.run("查未收录的异常");assertEquals("PARTIAL",run.status());assertTrue(run.needHuman());assertEquals("HUMAN_REVIEW_REQUIRED",run.statusReason());
    }
    @Test void retractionDuringModelAnswerRejectsStaleCitation()throws Exception {
        String id=published();seed(id);modelChain(id,m->{admin();review.retract(id,1L);});
        actor(111024,"SUBMITTER");var run=agent.run("查经验");assertEquals("PARTIAL",run.status());assertEquals("STALE_GRAPH_SOURCE",run.statusReason());assertFalse(run.answer().contains(sourceId(id)));
    }
    @Test void disabledGraphDuringModelAnswerCannotReturnOldEvidence()throws Exception {
        String id=published();seed(id);modelChain(id,m->config.setGraphSearchEnabled(false));var run=agent.run("查经验");assertEquals("STALE_GRAPH_SOURCE",run.statusReason());assertEquals("PARTIAL",run.status());
    }
    @Test void foreignTicketQueryCannotReadItsTitleBeforeGraphLookup()throws Exception {
        String id=published();seed(id);long ticket=knowledge.candidate(id,false).ticketId();actor(111025,"SUBMITTER");
        assertEquals(403,assertThrows(ApiFailure.class,()->tools.execute("searchExperienceGraph",json.writeValueAsString(Map.of("query","工单"+ticket+"处理建议")),"run")).status());verifyNoInteractions(retrieval);
    }
    @Test void twoDifferentConditionsNeverSharePathsOrPreconditions()throws Exception {
        String first=published();String second=approved(prepare(processing(),condition+"不可用"));worker.process(message(second,"INDEX",1));seed(first,second);
        var rows=found();assertEquals(2,rows.size());var a=(Map<?,?>)rows.get(0);var b=(Map<?,?>)rows.get(1);
        assertNotEquals(a.get("graphHash"),b.get("graphHash"));assertFalse(a.get("facts").toString().contains(condition+"不可用"));assertTrue(b.get("facts").toString().contains(condition+"不可用"));
        assertNotEquals(a.get("sourceId"),b.get("sourceId"));
    }
    @Test void pgReadFailureReturnsDegradedEmptyGraph()throws Exception {
        String id=published();seed(id);doThrow(new IllegalStateException("private DB error")).when(reader).read(any());
        var result=search.search("配置");assertEquals(List.of(),result.get("sources"));assertEquals(List.of("GRAPH_UNAVAILABLE"),result.get("degraded"));assertFalse(result.toString().contains("private DB"));
    }
    @Test void graphToolsDoNotChangeTicketOrGrantAssignmentAuthority()throws Exception {
        String id=published();seed(id);long ticket=knowledge.candidate(id,false).ticketId();var before=db.queryForMap("SELECT status,version,assignee_id FROM ticket WHERE id=?",ticket);
        actor(111024,"SUBMITTER");modelChain(id,m->{});assertEquals("SUCCEEDED",agent.run("查经验").status());
        assertEquals(before,db.queryForMap("SELECT status,version,assignee_id FROM ticket WHERE id=?",ticket));assertFalse(tools.definitions().stream().anyMatch(t->t.getToolDefinition().name().equals("prepareAssignTicket")));
    }
    @Test void graphEmptyCanFallBackToExistingTextTool()throws Exception {
        when(retrieval.search(anyString())).thenReturn(new KnowledgeSearchResult(List.of(new FusedHit(new KnowledgeChunk("faq/g4-fixture.md","FAQ","排查",0,"先检查连接"),0.02,Set.of(RetrievalSource.KEYWORD))),List.of(),0));
        var index=new java.util.concurrent.atomic.AtomicInteger();when(model.call(anyList(),anyList(),anyInt())).thenAnswer(c->switch(index.getAndIncrement()) {
            case 0->tool("searchExperienceGraph","{\"query\":\"配置\"}");case 1->tool("searchKnowledge","{\"query\":\"配置\"}");default->answer(List.of("faq/g4-fixture.md#0"),false);});
        var run=agent.run("查询排查经验");assertEquals("SUCCEEDED",run.status());assertEquals(3,run.modelCalls());assertEquals(2,run.toolCalls());assertTrue(run.answer().contains("faq/g4-fixture.md#0"));
    }
    @Test void toolRejectsScopeEscalationAndSqlArguments()throws Exception {
        assertThrows(ApiFailure.class,()->tools.execute("searchExperienceGraph","{\"query\":\"配置\",\"scope\":\"ALL\",\"sql\":\"SELECT *\"}","run"));verifyNoInteractions(retrieval);
    }

    private com.smartticket.agent.AgentModel.Reply decision(boolean human,String state,String reason,String detail,List<String> ids)throws Exception {
        return new com.smartticket.agent.AgentModel.Reply(new org.springframework.ai.chat.messages.AssistantMessage(json.writeValueAsString(
                Map.of("answer",state.equals("NEEDS_CLARIFICATION")?"请说明目前出现的具体报错是什么？":"请按资料核对适用条件和配置，记录结果；这不保证业务问题已解决。",
                        "needHuman",human,"sourceIds",ids,"handoff",Map.of("state",state,"reason",reason,"detail",detail)))),50);
    }
    @Test void supportedGraphExplanationRepairsUnnecessaryHandoffWithoutAnotherToolCall()throws Exception {
        String id=published();seed(id);var index=new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(anyList(),anyList(),anyInt())).thenAnswer(call->switch(index.getAndIncrement()) {
            case 0->tool("searchExperienceGraph","{\"query\":\"配置\"}");
            case 1->decision(true,"ANSWERED","NONE","",List.of(sourceId(id)));
            default->{assertTrue(((List<?>)call.getArgument(1)).isEmpty());yield decision(false,"ANSWERED","NONE","",List.of(sourceId(id)));}
        });
        var run=agent.run("请解释已审核经验的适用条件和步骤");
        assertEquals("SUCCEEDED",run.status());assertFalse(run.needHuman());assertEquals("ANSWER",run.nextAction());
        assertEquals("NONE",run.handoffReason());assertEquals(3,run.modelCalls());assertEquals(1,run.toolCalls());
        assertTrue(run.answer().contains(sourceId(id)));
    }
    @Test void invalidDemoScopeReasonGetsOneCorrectionAndNoHandoff()throws Exception {
        var index=new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(anyList(),anyList(),anyInt())).thenAnswer(call->index.getAndIncrement()==0
                ?decision(true,"HUMAN_ACTION_REQUIRED","DEMO_SCOPE","只是合成演示需要转人工",List.of())
                :decision(false,"ANSWERED","NONE","",List.of()));
        var run=agent.run("什么是合成演示数据？");
        assertFalse(run.needHuman());assertEquals("SUCCEEDED",run.status());assertEquals(2,run.modelCalls());assertEquals(0,run.toolCalls());
    }
    @Test void repeatedUnjustifiedHandoffWaitsForInputRatherThanCreatingHumanWork()throws Exception {
        when(model.call(anyList(),anyList(),anyInt())).thenAnswer(call->answer(List.of(),true));
        var run=agent.run("请解释PUT全量更新");
        assertEquals("WAITING_INPUT",run.status());assertEquals("HANDOFF_REASON_REQUIRED",run.statusReason());
        assertFalse(run.needHuman());assertEquals("ASK_USER",run.nextAction());assertEquals(2,run.modelCalls());assertEquals(0,run.toolCalls());
        verifyNoInteractions(retrieval);
    }
    @Test void clarifyingQuestionAfterEmptySearchDoesNotPretendToHaveAnAnswerOrEscalate()throws Exception {
        when(retrieval.search(anyString())).thenReturn(new KnowledgeSearchResult(List.of(),List.of(),0));
        var index=new java.util.concurrent.atomic.AtomicInteger();when(model.call(anyList(),anyList(),anyInt())).thenAnswer(call->index.getAndIncrement()==0
                ?tool("searchExperienceGraph","{\"query\":\"账号异常\"}")
                :decision(false,"NEEDS_CLARIFICATION","USER_CONTEXT_REQUIRED","请补充目前遇到的具体报错信息",List.of()));
        var run=agent.run("我的账号有问题");
        assertEquals("WAITING_INPUT",run.status());assertEquals("CLARIFICATION_REQUIRED",run.statusReason());
        assertFalse(run.needHuman());assertEquals("ASK_USER",run.nextAction());assertEquals(1,run.toolCalls());assertTrue(run.answer().contains("报错"));
    }
    @Test void missingRealBusinessRecordRemainsAConcreteHumanBlocker()throws Exception {
        when(retrieval.search(anyString())).thenReturn(new KnowledgeSearchResult(List.of(),List.of(),0));
        var index=new java.util.concurrent.atomic.AtomicInteger();when(model.call(anyList(),anyList(),anyInt())).thenAnswer(call->index.getAndIncrement()==0
                ?tool("searchKnowledge","{\"query\":\"退款到账记录\"}")
                :decision(true,"INSUFFICIENT_EVIDENCE","CRITICAL_FACTS_MISSING","缺少当前订单的真实退款到账记录",List.of()));
        var run=agent.run("这笔退款到账了吗？");
        assertEquals("PARTIAL",run.status());assertTrue(run.needHuman());assertEquals("CRITICAL_FACTS_MISSING",run.handoffReason());
        assertEquals("缺少当前订单的真实退款到账记录",run.handoffDetail());assertEquals(2,run.modelCalls());
    }
    @Test void actualManualOperationIsDifferentFromExplainingItsProcedure()throws Exception {
        when(model.call(anyList(),anyList(),anyInt())).thenReturn(decision(true,"HUMAN_ACTION_REQUIRED","HUMAN_OPERATION_REQUIRED","用户要求人工核实账号归属并办理申诉",List.of()));
        var run=agent.run("现在为我办理需要人工核验的账号申诉");
        assertTrue(run.needHuman());assertEquals("HUMAN_SUPPORT",run.nextAction());assertEquals("HUMAN_OPERATION_REQUIRED",run.handoffReason());
        assertEquals(1,run.modelCalls());assertEquals(0,run.toolCalls());
    }
    @Test void handoffCorrectionCannotObtainAdditionalToolAuthority()throws Exception {
        var index=new java.util.concurrent.atomic.AtomicInteger();when(model.call(anyList(),anyList(),anyInt())).thenAnswer(call->index.getAndIncrement()==0
                ?answer(List.of(),true):tool("searchKnowledge","{\"query\":\"越权重试\"}"));
        var run=agent.run("解释一个简单规则");
        assertEquals("STOPPED",run.status());assertEquals("EXECUTION_STOPPED",run.statusReason());assertEquals(0,run.toolCalls());verifyNoInteractions(retrieval);
    }
    @Test void unavailableRepairCallBudgetDoesNotSilentlyEscalate()throws Exception {
        int previous=runtimeConfig.getMaxModelCalls();
        try {
            runtimeConfig.setMaxModelCalls(1);
            when(model.call(anyList(),anyList(),anyInt())).thenAnswer(call->answer(List.of(),true));
            var run=agent.run("解释PUT规则");
            assertEquals("WAITING_INPUT",run.status());assertFalse(run.needHuman());assertEquals("ASK_USER",run.nextAction());
            assertEquals(1,run.modelCalls());assertEquals(0,run.toolCalls());
        } finally {runtimeConfig.setMaxModelCalls(previous);}
    }
}

