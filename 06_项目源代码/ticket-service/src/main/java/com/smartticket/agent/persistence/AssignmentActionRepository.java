package com.smartticket.agent.persistence;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Confirmation row locks participate in the caller's business transaction. */
@Repository
public class AssignmentActionRepository {
    private final JdbcTemplate db;

    public AssignmentActionRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void prepare(String id, long actor, long ticket, long version, long handler, String run) {
        db.update("INSERT INTO agent_action(action_id,actor_id,ticket_id,expected_version,assignee_id,run_id,expires_at)"
                + " VALUES(?,?,?,?,?,?,TIMESTAMPADD(MINUTE,5,NOW(3)))", id, actor, ticket, version, handler, run);
    }

    public List<Map<String, Object>> lock(String id) {
        return db.queryForList("SELECT * FROM agent_action WHERE action_id=? FOR UPDATE", id);
    }

    public List<Long> lockTicketVersion(long id) {
        return db.queryForList("SELECT version FROM ticket WHERE id=? AND deleted=0 FOR UPDATE", Long.class, id);
    }

    public void executed(String id, String result) {
        db.update("UPDATE agent_action SET status='EXECUTED',result=? WHERE action_id=?", result, id);
    }
}
