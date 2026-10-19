package com.smartticket.phase3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.entity.Ticket;
import com.smartticket.mq.TicketAnalysisMessage;
import com.smartticket.vo.TicketAnalysisVO;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@Repository
public class Phase3Repository {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Phase3Properties config;

    public record Task(String id, long ticketId, long version, int attempts, String token) {}

    public record Event(String id, String payload, int attempts, String token) {}

    public Phase3Repository(
            JdbcTemplate db,
            org.springframework.transaction.PlatformTransactionManager tm,
            ObjectMapper json,
            Phase3Properties config) {
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.json = json;
        this.config = config;
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Serialization failed", e);
        }
    }

    public String createTask(Ticket ticket, int generation, String replay) {
        String id = UUID.randomUUID().toString(), trace = UUID.randomUUID().toString();
        long version = ticket.getVersion() == null ? 0 : ticket.getVersion();
        db.update(
                "INSERT INTO"
                    + " ai_analysis_task(task_id,ticket_id,input_version,generation,replay_of_task_id,trace_id)"
                    + " VALUES(?,?,?,?,?,?)",
                id,
                ticket.getId(),
                version,
                generation,
                replay,
                trace);
        enqueue(id, ticket.getId(), version, trace, 0);
        return id;
    }

    private void enqueue(String task, long ticket, long version, String trace, int seq) {
        String event = UUID.randomUUID().toString();
        var msg =
                new TicketAnalysisMessage(
                        1, event, task, ticket, version, trace, OffsetDateTime.now().toString());
        db.update(
                "INSERT INTO outbox_event(event_id,task_id,dispatch_sequence,event_type,payload)"
                        + " VALUES(?,?,?,'ticket.analysis.requested',?)",
                event,
                task,
                seq,
                encode(msg));
    }

    public Optional<Event> claimEvent() {
        return tx.execute(
                s -> {
                    var rows =
                            db.queryForList(
                                    "SELECT * FROM outbox_event WHERE (status='PENDING' AND"
                                        + " (next_attempt_at IS NULL OR next_attempt_at<=NOW(3)))"
                                        + " OR (status='SENDING' AND lease_until<NOW(3)) ORDER BY"
                                        + " id LIMIT 1 FOR UPDATE SKIP LOCKED");
                    if (rows.isEmpty()) return Optional.empty();
                    var r = rows.get(0);
                    String token = UUID.randomUUID().toString();
                    db.update(
                            "UPDATE outbox_event SET status='SENDING',"
                                + " attempts=attempts+1,lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,NOW(3))"
                                + " WHERE id=?",
                            token,
                            config.getLeaseSeconds(),
                            r.get("id"));
                    return Optional.of(
                            new Event(
                                    (String) r.get("event_id"),
                                    (String) r.get("payload"),
                                    ((Number) r.get("attempts")).intValue() + 1,
                                    token));
                });
    }

    public void published(Event e) {
        db.update(
                "UPDATE outbox_event SET"
                    + " status='SENT',lease_token=NULL,lease_until=NULL,error_summary=NULL WHERE"
                    + " event_id=? AND status='SENDING' AND lease_token=?",
                e.id(),
                e.token());
    }

    public void publishFailed(Event e) {
        db.update(
                "UPDATE outbox_event SET"
                    + " status='PENDING',next_attempt_at=TIMESTAMPADD(SECOND,?,NOW(3)),lease_token=NULL,lease_until=NULL,error_summary='Broker"
                    + " publication failed' WHERE event_id=? AND lease_token=?",
                Math.min(60, e.attempts() * 5),
                e.id(),
                e.token());
    }

    public Optional<Task> claimTask(TicketAnalysisMessage m) {
        return tx.execute(
                s -> {
                    var rows =
                            db.queryForList(
                                    "SELECT * FROM ai_analysis_task WHERE task_id=? FOR UPDATE",
                                    m.taskId());
                    if (rows.isEmpty()) throw new ApiFailure(400, 40000, "未知分析任务");
                    var r = rows.get(0);
                    if (((Number) r.get("ticket_id")).longValue() != m.ticketId()
                            || ((Number) r.get("input_version")).longValue() != m.inputVersion()
                            || !Objects.equals(r.get("trace_id"), m.traceId()))
                        throw new ApiFailure(400, 40000, "消息与任务不匹配");
                    var events =
                            db.queryForList(
                                    "SELECT task_id,payload FROM outbox_event WHERE event_id=?",
                                    m.eventId());
                    if (events.isEmpty()
                            || !Objects.equals(events.get(0).get("task_id"), m.taskId()))
                        throw new ApiFailure(400, 40000, "消息事件不匹配");
                    String status = (String) r.get("status");
                    if (!List.of("PENDING", "RUNNING").contains(status)) return Optional.empty();
                    if ("RUNNING".equals(status)
                            && r.get("lease_until") != null
                            && localTime(r.get("lease_until")).isAfter(LocalDateTime.now()))
                        return Optional.empty();
                    if (r.get("next_retry_at") != null
                            && localTime(r.get("next_retry_at")).isAfter(LocalDateTime.now()))
                        return Optional.empty();
                    if (((Number) r.get("attempts")).intValue() >= config.getMaxAttempts()) {
                        db.update(
                                "UPDATE ai_analysis_task SET"
                                        + " status='FAILED',error_code='RETRY_EXHAUSTED' WHERE"
                                        + " task_id=?",
                                m.taskId());
                        return Optional.empty();
                    }
                    String token = UUID.randomUUID().toString();
                    db.update(
                            "UPDATE ai_analysis_task SET"
                                + " status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,NOW(3)),next_retry_at=NULL"
                                + " WHERE task_id=?",
                            token,
                            config.getLeaseSeconds(),
                            m.taskId());
                    return Optional.of(
                            new Task(
                                    m.taskId(),
                                    m.ticketId(),
                                    m.inputVersion(),
                                    ((Number) r.get("attempts")).intValue() + 1,
                                    token));
                });
    }

    public boolean finish(Task t, TicketAnalysisVO result, String forcedStatus) {
        return tx.execute(
                s -> {
                    var tickets =
                            db.queryForList(
                                    "SELECT version,deleted FROM ticket WHERE id=? FOR UPDATE",
                                    t.ticketId());
                    var tasks =
                            db.queryForList(
                                    "SELECT status,lease_token FROM ai_analysis_task WHERE"
                                            + " task_id=? FOR UPDATE",
                                    t.id());
                    if (tasks.isEmpty()
                            || !"RUNNING".equals(tasks.get(0).get("status"))
                            || !Objects.equals(t.token(), tasks.get(0).get("lease_token")))
                        return false;
                    boolean stale =
                            tickets.isEmpty()
                                    || ((Number) tickets.get(0).get("version")).longValue()
                                            != t.version()
                                    || ((Number) tickets.get(0).get("deleted")).intValue() != 0;
                    String status = stale ? "STALE" : forcedStatus;
                    if (status == null) status = result.degraded() ? "DEGRADED" : "SUCCEEDED";
                    if ("SUCCEEDED".equals(status)
                            || ("DEGRADED".equals(status)
                                    && result != null
                                    && result.category() != null))
                        db.update(
                                "INSERT INTO"
                                    + " ai_analysis_result(task_id,input_version,category,priority,confidence,keywords,reason,source_type,model,prompt_version,cost_ms)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                t.id(),
                                t.version(),
                                result.category(),
                                result.priority(),
                                result.confidence(),
                                encode(result.keywords()),
                                result.reason(),
                                result.degraded() ? "RULE" : "MODEL",
                                result.model(),
                                result.promptVersion(),
                                result.costMs());
                    db.update(
                            "UPDATE ai_analysis_task SET"
                                + " status=?,lease_token=NULL,lease_until=NULL,error_code=?,error_summary=?"
                                + " WHERE task_id=?",
                            status,
                            result != null && result.degraded()
                                    ? String.valueOf(result.degradedReasonCode())
                                    : null,
                            result != null && result.degraded() ? result.degradedReason() : null,
                            t.id());
                    return true;
                });
    }

    public boolean retry(Task t, String reason) {
        return tx.execute(
                s -> {
                    var rows =
                            db.queryForList(
                                    "SELECT * FROM ai_analysis_task WHERE task_id=? FOR UPDATE",
                                    t.id());
                    if (rows.isEmpty()
                            || !Objects.equals(rows.get(0).get("lease_token"), t.token()))
                        return false;
                    boolean terminal = t.attempts() >= config.getMaxAttempts();
                    db.update(
                            "UPDATE ai_analysis_task SET"
                                + " status=?,error_code=?,error_summary='Transient model"
                                + " failure',lease_token=NULL,lease_until=NULL,next_retry_at=TIMESTAMPADD(SECOND,?,NOW(3))"
                                + " WHERE task_id=?",
                            terminal ? "FAILED" : "PENDING",
                            reason,
                            t.attempts() == 1 ? 5 : 30,
                            t.id());
                    return terminal;
                });
    }

    public void recoverAndSchedule() {
        tx.executeWithoutResult(
                s -> {
                    var rows =
                            db.queryForList(
                                    "SELECT * FROM ai_analysis_task WHERE (status='PENDING' AND"
                                            + " next_retry_at<=NOW(3)) OR (status='RUNNING' AND"
                                            + " lease_until<NOW(3)) ORDER BY id LIMIT 20 FOR UPDATE"
                                            + " SKIP LOCKED");
                    for (var r : rows) {
                        int attempts = ((Number) r.get("attempts")).intValue();
                        String id = (String) r.get("task_id");
                        if (attempts >= config.getMaxAttempts()) {
                            db.update(
                                    "UPDATE ai_analysis_task SET"
                                        + " status='FAILED',error_code='LEASE_EXHAUSTED',lease_token=NULL,lease_until=NULL"
                                        + " WHERE task_id=?",
                                    id);
                            continue;
                        }
                        int seq =
                                db.queryForObject(
                                        "SELECT COALESCE(MAX(dispatch_sequence),-1)+1 FROM"
                                                + " outbox_event WHERE task_id=?",
                                        Integer.class,
                                        id);
                        enqueue(
                                id,
                                ((Number) r.get("ticket_id")).longValue(),
                                ((Number) r.get("input_version")).longValue(),
                                (String) r.get("trace_id"),
                                seq);
                        db.update(
                                "UPDATE ai_analysis_task SET"
                                    + " status='PENDING',next_retry_at=NULL,lease_token=NULL,lease_until=NULL"
                                    + " WHERE task_id=?",
                                id);
                    }
                });
    }

    public Map<String, Object> analysis(long ticketId) {
        var rows =
                db.queryForList(
                        "SELECT * FROM ai_analysis_task WHERE ticket_id=? ORDER BY id DESC LIMIT 1",
                        ticketId);
        if (rows.isEmpty()) return Map.of("status", "NOT_REQUESTED");
        var row = rows.get(0);
        var results =
                db.queryForList(
                        "SELECT"
                            + " input_version,category,priority,confidence,keywords,reason,source_type,model,prompt_version,cost_ms"
                            + " FROM ai_analysis_result WHERE task_id=?",
                        row.get("task_id"));
        Long current =
                db.queryForObject("SELECT version FROM ticket WHERE id=?", Long.class, ticketId);
        boolean stale = ((Number) row.get("input_version")).longValue() != current;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", row.get("task_id"));
        out.put("status", stale ? "STALE" : row.get("status"));
        out.put("attempts", row.get("attempts"));
        out.put("errorCode", row.get("error_code"));
        out.put("suggestion", stale || results.isEmpty() ? null : results.get(0));
        return out;
    }

    public String replay(Ticket ticket) {
        return tx.execute(
                s -> {
                    var rows =
                            db.queryForList(
                                    "SELECT version FROM ticket WHERE id=? FOR UPDATE",
                                    ticket.getId());
                    if (rows.isEmpty()) throw ApiFailure.conflict();
                    ticket.setVersion(((Number) rows.get(0).get("version")).longValue());
                    var old =
                            db.queryForList(
                                    "SELECT task_id,status,generation FROM ai_analysis_task WHERE"
                                            + " ticket_id=? AND input_version=? ORDER BY generation"
                                            + " DESC LIMIT 1 FOR UPDATE",
                                    ticket.getId(),
                                    ticket.getVersion());
                    if (!old.isEmpty()
                            && List.of("PENDING", "RUNNING").contains(old.get(0).get("status")))
                        return (String) old.get(0).get("task_id");
                    return createTask(
                            ticket,
                            old.isEmpty()
                                    ? 0
                                    : ((Number) old.get(0).get("generation")).intValue() + 1,
                            old.isEmpty() ? null : (String) old.get(0).get("task_id"));
                });
    }

    public String replayWithKey(Ticket ticket, String key) {
        try {
            UUID.fromString(key);
        } catch (Exception invalid) {
            throw new ApiFailure(400, 40000, "需要UUID格式的Idempotency-Key");
        }
        long actor = ActorContext.require().id();
        return tx.execute(
                s -> {
                    db.update(
                            "INSERT IGNORE INTO"
                                + " analysis_replay_request(actor_id,request_key,ticket_id)"
                                + " VALUES(?,?,?)",
                            actor,
                            key,
                            ticket.getId());
                    var row =
                            db.queryForMap(
                                    "SELECT ticket_id,task_id FROM analysis_replay_request WHERE"
                                        + " actor_id=? AND request_key=? FOR UPDATE",
                                    actor,
                                    key);
                    if (((Number) row.get("ticket_id")).longValue() != ticket.getId())
                        throw ApiFailure.conflict();
                    if (row.get("task_id") != null) return (String) row.get("task_id");
                    String task = replay(ticket);
                    db.update(
                            "UPDATE analysis_replay_request SET task_id=? WHERE actor_id=? AND"
                                + " request_key=?",
                            task,
                            actor,
                            key);
                    return task;
                });
    }

    public void invalidate(long id) {
        db.update(
                "INSERT INTO cache_invalidation(ticket_id) VALUES(?) ON DUPLICATE KEY UPDATE"
                        + " generation=generation+1,updated_at=NOW(3)",
                id);
    }

    public JdbcTemplate db() {
        return db;
    }

    public static LocalDateTime localTime(Object value) {
        return value instanceof LocalDateTime t
                ? t
                : ((java.sql.Timestamp) value).toLocalDateTime();
    }
}
