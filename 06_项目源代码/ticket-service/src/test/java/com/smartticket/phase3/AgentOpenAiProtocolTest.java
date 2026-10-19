package com.smartticket.phase3;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.agent.AgentService;
import com.smartticket.dto.TicketCreateDTO;
import com.smartticket.rag.*;
import com.smartticket.service.TicketService;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Real Spring AI HTTP/tool-call protocol against an offline local vendor fixture. */
@SpringBootTest(
        properties = {
            "spring.ai.openai.api-key=offline-protocol-key",
            "spring.ai.openai.chat.options.model=tool-fixture",
            "smartticket.ai.enabled=true",
            "smartticket.phase3.security-enabled=false",
            "smartticket.phase3.record-tasks=false",
            "smartticket.phase3.async-enabled=false"
        })
class AgentOpenAiProtocolTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    static volatile long ticketId;
    static volatile String mode = "chain";
    static final HttpServer SERVER;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext(
                    "/v1/chat/completions",
                    exchange -> {
                        REQUESTS.add(
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        int index = REQUESTS.size();
                        String name =
                                index == 2 && !mode.equals("repeat")
                                        ? "searchKnowledge"
                                        : "getTicketDetail";
                        String args =
                                name.equals("searchKnowledge")
                                        ? JSON.writeValueAsString(Map.of("query", mode.equals("ticket_query") ? ticketId + "号工单处理建议" : "支付失败"))
                                        : "{\"ticketId\":" + ticketId + "}";
                        Map<String, Object> message = new LinkedHashMap<>();
                        message.put("role", "assistant");
                        boolean tool = mode.equals("repeat") || index < 3;
                        if (tool) {
                            message.put("content", "");
                            message.put(
                                    "tool_calls",
                                    List.of(
                                            Map.of(
                                                    "id",
                                                    "call-" + index,
                                                    "type",
                                                    "function",
                                                    "function",
                                                    Map.of("name", name, "arguments", args))));
                        } else message.put("content", JSON.writeValueAsString(Map.of("answer", "工单待分配；请核对支付，来源faq/protocol.md#0", "needHuman", false, "sourceIds", List.of("faq/protocol.md#0"))));
                        var body =
                                Map.of(
                                        "id",
                                        "offline-" + index,
                                        "object",
                                        "chat.completion",
                                        "created",
                                        1758700000,
                                        "model",
                                        "tool-fixture",
                                        "choices",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        0,
                                                        "message",
                                                        message,
                                                        "finish_reason",
                                                        tool ? "tool_calls" : "stop")),
                                        "usage",
                                        Map.of(
                                                "prompt_tokens",
                                                24,
                                                "completion_tokens",
                                                12,
                                                "total_tokens",
                                                36));
                        byte[] bytes = JSON.writeValueAsBytes(body);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            SERVER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry r) {
        r.add(
                "spring.ai.openai.base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @Autowired AgentService agent;
    @Autowired TicketService tickets;
    @Autowired JdbcTemplate db;
    @MockBean KnowledgeSearchService knowledge;

    @BeforeEach
    void prepare() {
        REQUESTS.clear();
        mode = "chain";
        ActorContext.set(new ActorContext.Actor(109001, "SUBMITTER"));
        var dto = new TicketCreateDTO();
        dto.setTitle("支付失败");
        dto.setCreatorId(109001L);
        ticketId = tickets.create(dto).getId();
        when(knowledge.search(anyString()))
                .thenReturn(
                        new KnowledgeSearchResult(
                                List.of(
                                        new FusedHit(
                                                new KnowledgeChunk(
                                                        "faq/protocol.md",
                                                        "FAQ",
                                                        "支付失败",
                                                        0,
                                                        "联系人工核对"),
                                                0.02,
                                                Set.of(RetrievalSource.KEYWORD))),
                                List.of(),
                                0));
    }

    @AfterEach
    void cleanup() {
        ActorContext.clear();
        db.update("DELETE FROM ticket_log WHERE ticket_id=?", ticketId);
        db.update("DELETE FROM ticket WHERE id=?", ticketId);
        db.update("DELETE FROM cache_invalidation WHERE ticket_id=?", ticketId);
        db.update(
                "DELETE FROM agent_step WHERE run_id IN (SELECT run_id FROM agent_run WHERE"
                        + " actor_id=109001)");
        db.update("DELETE FROM agent_run WHERE actor_id=109001");
        db.update("DELETE FROM ai_usage_budget WHERE actor_id=109001");
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Test
    void vendorToolCallsExecuteRealBusinessToolsAndReturnProtocolResults() throws Exception {
        var run = agent.run("查工单并检索办法");
        assertEquals("SUCCEEDED", run.status());
        assertEquals(3, REQUESTS.size());
        assertEquals(2, run.toolCalls());
        assertEquals(108, run.tokens());
        var first = JSON.readTree(REQUESTS.get(0));
        assertTrue(first.get("tools").toString().contains("getTicketDetail"));
        assertTrue(first.get("tools").toString().contains("additionalProperties"));
        assertEquals("json_object", first.get("response_format").get("type").asText());
        var second = JSON.readTree(REQUESTS.get(1));
        assertTrue(second.get("messages").toString().contains("tool_call_id"));
        assertTrue(second.get("messages").toString().contains("call-1"));
        assertTrue(REQUESTS.get(2).contains("faq/protocol.md#0"));
        assertEquals("SUCCESS", run.steps().get(0).outcome());
    }

    @Test
    void ticketNumberQueryIsEnrichedAndReturnedThroughRealToolProtocol() throws Exception {
        mode = "ticket_query";
        var run = agent.run("查工单并给出处理建议");
        assertEquals("SUCCEEDED", run.status());
        verify(knowledge).search("支付失败");
        String returned = REQUESTS.get(2);
        assertTrue(returned.contains("AUTHORIZED_TICKET_TITLE"));
        assertTrue(returned.contains("effectiveQuery"));
        assertTrue(returned.contains("支付失败"));
        assertEquals(2, run.toolCalls());
    }

    @Test
    void automaticFrameworkLoopIsDisabledAndApplicationStopsRepeatedCalls() {
        mode = "repeat";
        var run = agent.run("反复查单");
        assertEquals("STOPPED", run.status());
        assertEquals(2, REQUESTS.size());
    }
}
