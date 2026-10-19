package com.smartticket.agent.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Execution audit contains identifiers and usage, never full user/model text. */
@Repository
public class AgentRunRepository {
    private final JdbcTemplate db;

    public AgentRunRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void start(String id, long actor) {
        db.update("INSERT INTO agent_run(run_id,actor_id,status) VALUES(?,?,'RUNNING')", id, actor);
    }

    public void step(String run, String call, String tool, String outcome, long costMs) {
        db.update("INSERT INTO agent_step(run_id,tool_call_id,tool_name,outcome,cost_ms) VALUES(?,?,?,?,?)",
                run, call, tool, outcome, costMs);
    }

    public void finish(String id, String status, int calls, int tools, int tokens, long costMs) {
        db.update("UPDATE agent_run SET status=?,model_calls=?,tool_calls=?,tokens_used=?,cost_ms=? WHERE run_id=?",
                status, calls, tools, tokens, costMs, id);
    }
}
