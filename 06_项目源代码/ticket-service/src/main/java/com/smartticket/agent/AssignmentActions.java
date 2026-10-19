package com.smartticket.agent;

import com.smartticket.agent.persistence.AssignmentActionRepository;
import com.smartticket.common.JsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.exception.ApiFailure;
import com.smartticket.security.AccessPolicy;
import com.smartticket.security.ActorContext;

import com.smartticket.dto.TicketAssignDTO;
import com.smartticket.mapper.TicketMapper;

import com.smartticket.service.TicketService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
public class AssignmentActions {
    private final AssignmentActionRepository repo;
    private final TicketMapper tickets;
    private final TicketService service;
    private final AccessPolicy access;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final ObjectMapper mapper;

    public AssignmentActions(
            AssignmentActionRepository r,
            TicketMapper t,
            TicketService s,
            AccessPolicy a,
            org.springframework.transaction.PlatformTransactionManager tm, JsonCodec json, ObjectMapper mapper) {
        repo = r;
        this.json = json;
        this.mapper = mapper;
        tickets = t;
        service = s;
        access = a;
        tx = new TransactionTemplate(tm);
    }

    public Map<String, Object> prepare(long ticketId, long handler, String runId) {
        var actor = ActorContext.require();
        access.admin();
        if (!access.validHandler(handler)) throw new ApiFailure(400, 40000, "处理人不存在或角色无效");
        var ticket = tickets.selectById(ticketId);
        if (ticket == null) throw new ApiFailure(404, 10003, "工单不存在");
        access.read(ticket);
        if ("CLOSED".equals(ticket.getStatus())) throw new ApiFailure(409, 10005, "已关闭工单不能指派");
        String action = UUID.randomUUID().toString();
        repo.prepare(action, actor.id(), ticketId, ticket.getVersion(), handler, runId);
        return Map.of(
                "actionId",
                action,
                "ticketId",
                ticketId,
                "assigneeId",
                handler,
                "expectedVersion",
                ticket.getVersion(),
                "status",
                "PENDING_CONFIRMATION",
                "expiresInSeconds",
                300,
                "summary",
                "确认后将工单指派给指定处理人；当前尚未执行");
    }

    public Object confirm(String actionId) {
        var actor = ActorContext.require();
        access.admin();
        return tx.execute(
                s -> {
                    var rows = repo.lock(actionId);
                    if (rows.isEmpty()) throw new ApiFailure(404, 40400, "操作不存在");
                    var row = rows.get(0);
                    if (((Number) row.get("actor_id")).longValue() != actor.id())
                        throw ApiFailure.forbidden();
                    if ("EXECUTED".equals(row.get("status"))) {
                        try {
                            return mapper
                                    .readValue((String) row.get("result"), Object.class);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    if ((row.get("expires_at") instanceof java.time.LocalDateTime time ? time : ((java.sql.Timestamp) row.get("expires_at")).toLocalDateTime())
                            .isBefore(java.time.LocalDateTime.now()))
                        throw new ApiFailure(409, 10012, "操作已过期，请重新提出");
                    long id = ((Number) row.get("ticket_id")).longValue();
                    var lock = repo.lockTicketVersion(id);
                    if (lock.isEmpty()
                            || lock.get(0).longValue()
                                    != ((Number) row.get("expected_version")).longValue())
                        throw ApiFailure.conflict();
                    long handler = ((Number) row.get("assignee_id")).longValue();
                    if (!access.validHandler(handler)) throw new ApiFailure(400, 40000, "处理人已失效");
                    TicketAssignDTO dto = new TicketAssignDTO();
                    dto.setAssigneeId(handler);
                    dto.setOperatorId(actor.id());
                    dto.setRemark("Agent确认指派 runId=" + row.get("run_id"));
                    var result = service.assign(id, dto);
                    repo.executed(actionId, json.encode(result));
                    return result;
                });
    }
}
