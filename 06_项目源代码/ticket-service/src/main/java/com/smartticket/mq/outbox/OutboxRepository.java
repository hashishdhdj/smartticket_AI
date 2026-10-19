package com.smartticket.mq.outbox;

import com.smartticket.common.JsonCodec;
import com.smartticket.config.RuntimeProperties;
import com.smartticket.mq.TicketAnalysisMessage;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Analysis outbox only. Claim commits before broker I/O; lease token fences late confirms. */
@Repository
public class OutboxRepository {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RuntimeProperties config;
    private final JsonCodec json;

    public record Event(String id, String payload, int attempts, String token) {}

    public OutboxRepository(JdbcTemplate db, PlatformTransactionManager tm,
                            RuntimeProperties config, JsonCodec json) {
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.config = config;
        this.json = json;
    }

    public void enqueue(String task, long ticket, long version, String trace, int seq) {
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
                json.encode(msg));
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

    public List<Map<String, Object>> statusCounts() {
        return db.queryForList("SELECT status,COUNT(*) AS total FROM outbox_event GROUP BY status");
    }
}
