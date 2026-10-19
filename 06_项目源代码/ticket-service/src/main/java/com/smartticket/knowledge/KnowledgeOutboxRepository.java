package com.smartticket.knowledge;

import com.smartticket.common.JsonCodec;
import com.smartticket.mq.KnowledgeJobMessage;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class KnowledgeOutboxRepository {
    public record Event(String id, String payload, String token, int attempts) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final KnowledgeWorkflowProperties config;

    public KnowledgeOutboxRepository(JdbcTemplate db, PlatformTransactionManager tm,
                                    JsonCodec json, KnowledgeWorkflowProperties config) {
        this.db = db; this.tx = new TransactionTemplate(tm); this.json = json; this.config = config;
    }

    public void enqueue(String job, String resolution, String kind, long revision, String trace, int sequence) {
        String id = UUID.randomUUID().toString();
        var message = new KnowledgeJobMessage(1, id, job, resolution, kind, revision, trace, OffsetDateTime.now().toString());
        db.update("INSERT INTO knowledge_outbox(event_id,job_id,dispatch_sequence,payload) VALUES(?,?,?,?)",
                id, job, sequence, json.encode(message));
    }

    public Optional<Event> claim() {
        return tx.execute(status -> {
            var rows = db.queryForList("SELECT * FROM knowledge_outbox WHERE (status='PENDING' AND"
                    + " (next_attempt_at IS NULL OR next_attempt_at<=NOW(3))) OR"
                    + " (status='SENDING' AND lease_until<NOW(3)) ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED");
            if (rows.isEmpty()) return Optional.empty();
            var row = rows.get(0);
            String token = UUID.randomUUID().toString();
            db.update("UPDATE knowledge_outbox SET status='SENDING',attempts=attempts+1,lease_token=?,"
                    + "lease_until=TIMESTAMPADD(SECOND,?,NOW(3)) WHERE event_id=?", token, config.getLeaseSeconds(), row.get("event_id"));
            return Optional.of(new Event((String)row.get("event_id"), (String)row.get("payload"), token,
                    ((Number)row.get("attempts")).intValue()+1));
        });
    }

    public void sent(Event event) {
        db.update("UPDATE knowledge_outbox SET status='SENT',lease_token=NULL,lease_until=NULL,error_code=NULL"
                + " WHERE event_id=? AND status='SENDING' AND lease_token=?", event.id(), event.token());
    }

    public void failed(Event event) {
        db.update("UPDATE knowledge_outbox SET status='PENDING',lease_token=NULL,lease_until=NULL,"
                + "next_attempt_at=TIMESTAMPADD(SECOND,?,NOW(3)),error_code='BROKER_UNAVAILABLE'"
                + " WHERE event_id=? AND lease_token=?", Math.min(60, event.attempts()*5), event.id(), event.token());
    }
}
