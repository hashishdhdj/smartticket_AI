package com.smartticket.agent;

import com.smartticket.dto.TicketAssignDTO;
import com.smartticket.mapper.TicketMapper;
import com.smartticket.phase3.*;
import com.smartticket.service.TicketService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
public class AssignmentActions {
    private final Phase3Repository repo;
    private final TicketMapper tickets;
    private final TicketService service;
    private final AccessPolicy access;
    private final TransactionTemplate tx;

    public AssignmentActions(
            Phase3Repository r,
            TicketMapper t,
            TicketService s,
            AccessPolicy a,
            org.springframework.transaction.PlatformTransactionManager tm) {
        repo = r;
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
        repo.db()
                .update(
                        "INSERT INTO"
                            + " agent_action(action_id,actor_id,ticket_id,expected_version,assignee_id,run_id,expires_at)"
                            + " VALUES(?,?,?,?,?,?,TIMESTAMPADD(MINUTE,5,NOW(3)))",
                        action,
                        actor.id(),
                        ticketId,
                        ticket.getVersion(),
                        handler,
                        runId);
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
                    var rows =
                            repo.db()
                                    .queryForList(
                                            "SELECT * FROM agent_action WHERE action_id=? FOR"
                                                    + " UPDATE",
                                            actionId);
                    if (rows.isEmpty()) throw new ApiFailure(404, 40400, "操作不存在");
                    var row = rows.get(0);
                    if (((Number) row.get("actor_id")).longValue() != actor.id())
                        throw ApiFailure.forbidden();
                    if ("EXECUTED".equals(row.get("status"))) {
                        try {
                            return new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readValue((String) row.get("result"), Object.class);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    if (Phase3Repository.localTime(row.get("expires_at"))
                            .isBefore(java.time.LocalDateTime.now()))
                        throw new ApiFailure(409, 10012, "操作已过期，请重新提出");
                    long id = ((Number) row.get("ticket_id")).longValue();
                    var lock =
                            repo.db()
                                    .queryForList(
                                            "SELECT version FROM ticket WHERE id=? AND deleted=0"
                                                    + " FOR UPDATE",
                                            id);
                    if (lock.isEmpty()
                            || ((Number) lock.get(0).get("version")).longValue()
                                    != ((Number) row.get("expected_version")).longValue())
                        throw ApiFailure.conflict();
                    long handler = ((Number) row.get("assignee_id")).longValue();
                    if (!access.validHandler(handler)) throw new ApiFailure(400, 40000, "处理人已失效");
                    TicketAssignDTO dto = new TicketAssignDTO();
                    dto.setAssigneeId(handler);
                    dto.setOperatorId(actor.id());
                    dto.setRemark("Agent确认指派 runId=" + row.get("run_id"));
                    var result = service.assign(id, dto);
                    repo.db()
                            .update(
                                    "UPDATE agent_action SET status='EXECUTED',result=? WHERE"
                                            + " action_id=?",
                                    repo.encode(result),
                                    actionId);
                    return result;
                });
    }
}
