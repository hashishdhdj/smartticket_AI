package com.smartticket.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.phase3.ActorContext;
import com.smartticket.phase3.ApiFailure;
import com.smartticket.rag.KnowledgeSearchResult;
import com.smartticket.rag.KnowledgeSearchService;
import com.smartticket.service.TicketService;
import com.smartticket.vo.TicketVO;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentToolsTest {
    final TicketService tickets = mock(TicketService.class);
    final KnowledgeSearchService knowledge = mock(KnowledgeSearchService.class);
    final ObjectMapper json = new ObjectMapper();
    final AgentTools tools = new AgentTools(tickets, knowledge, mock(AssignmentActions.class), json);

    @BeforeEach void identity() {
        ActorContext.set(new ActorContext.Actor(100, "SUBMITTER"));
        when(knowledge.search(anyString())).thenReturn(new KnowledgeSearchResult(List.of(), List.of(), 1));
    }
    @AfterEach void clear() { ActorContext.clear(); }

    private Map<?, ?> search(String q) throws Exception {
        return (Map<?, ?>) tools.execute("searchKnowledge", json.writeValueAsString(Map.of("query", q)), "run-test");
    }
    private void title(String title) {
        var ticket = mock(TicketVO.class);
        when(ticket.getTitle()).thenReturn(title);
        when(tickets.getById(123L)).thenReturn(ticket);
    }

    @Test void ticketOnlyQueryUsesAuthorizedTitleAndRecordsBothQueries() throws Exception {
        title("银行已扣款无订单");
        var result = search("工单123 处理建议");
        verify(tickets).getById(123L);
        verify(knowledge).search("银行已扣款无订单");
        assertEquals("工单123 处理建议", result.get("requestedQuery"));
        assertEquals("银行已扣款无订单", result.get("effectiveQuery"));
        assertEquals("AUTHORIZED_TICKET_TITLE", result.get("queryOrigin"));
    }
    @Test void handlesSpacesHashAndGenericPunctuation() throws Exception {
        title("登录验证码异常");
        search("请查询 工单 #123号 的解决方案？");
        verify(knowledge).search("登录验证码异常");
    }
    @Test void ticketNumberBeforeNameIsAlsoEnriched() throws Exception {
        title("银行已扣款无订单");
        var result=search("123号工单 处理建议");
        assertEquals("银行已扣款无订单", result.get("effectiveQuery"));
        assertEquals("AUTHORIZED_TICKET_TITLE", result.get("queryOrigin"));
        verify(tickets).getById(123L);
    }
    @Test void reversedNumberQueryStillCannotReadForeignTitle() {
        when(tickets.getById(123L)).thenThrow(new ApiFailure(403,40300,"无权访问"));
        assertThrows(ApiFailure.class,()->search("123号工单 处理建议"));
        verify(knowledge, never()).search(anyString());
    }
    @Test void unauthorizedTicketNeverReachesKnowledgeOrLeaksTitle() {
        when(tickets.getById(123L)).thenThrow(new ApiFailure(403, 40300, "无权访问"));
        var error = assertThrows(ApiFailure.class, () -> search("工单123处理建议"));
        assertEquals(403, error.status());
        verify(knowledge, never()).search(anyString());
    }
    @Test void absentTicketDoesNotSearch() {
        when(tickets.getById(123L)).thenThrow(new ApiFailure(404, 10003, "工单不存在"));
        assertThrows(ApiFailure.class, () -> search("工单123"));
        verify(knowledge, never()).search(anyString());
    }
    @Test void preservesTopicBearingQueriesWithoutReadingTicket() throws Exception {
        var result = search("工单123 登录验证码不正确怎么处理");
        verifyNoInteractions(tickets);
        verify(knowledge).search("工单123 登录验证码不正确怎么处理");
        assertEquals("MODEL_QUERY", result.get("queryOrigin"));
    }
    @Test void doesNotGuessBetweenMultipleTickets() throws Exception {
        search("工单123和工单456处理建议");
        verifyNoInteractions(tickets);
        verify(knowledge).search("工单123和工单456处理建议");
    }
    @Test void rejectsInvalidTicketNumbersBeforeDatabaseOrSearch() {
        assertThrows(ApiFailure.class, () -> search("工单0处理建议"));
        assertThrows(ApiFailure.class, () -> search("工单9999999999999999999处理建议"));
        verifyNoInteractions(tickets);
        verify(knowledge, never()).search(anyString());
    }
    @Test void emptyTitleRequiresSpecificQuery() {
        title("  ");
        assertThrows(ApiFailure.class, () -> search("工单123处理建议"));
        verify(knowledge, never()).search(anyString());
    }
    @Test void descriptionIsNotAddedToSearch() throws Exception {
        var ticket = mock(TicketVO.class);
        when(ticket.getTitle()).thenReturn("银行已扣款无订单");
        when(tickets.getById(123L)).thenReturn(ticket);
        search("工单123处理建议");
        verify(ticket, never()).getDescription();
    }
    @Test void identityAndStrictParametersStillApply() {
        assertThrows(ApiFailure.class, () -> tools.execute("searchKnowledge", "{\"query\":\"工单123\",\"actorId\":2}", "run"));
        ActorContext.clear();
        assertThrows(ApiFailure.class, () -> search("工单123"));
        verifyNoInteractions(tickets);
        verify(knowledge, never()).search(anyString());
    }
}
