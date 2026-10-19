package com.smartticket.cache;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CacheInvalidationRepository {
    private final JdbcTemplate db;

    public CacheInvalidationRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void invalidate(long ticket) {
        db.update("INSERT INTO cache_invalidation(ticket_id) VALUES(?) ON DUPLICATE KEY UPDATE"
                + " generation=generation+1,updated_at=NOW(3)", ticket);
    }

    public List<Map<String, Object>> pending() {
        return db.queryForList("SELECT ticket_id,generation FROM cache_invalidation ORDER BY updated_at LIMIT 50");
    }

    public void completed(long ticket, long generation) {
        db.update("DELETE FROM cache_invalidation WHERE ticket_id=? AND generation=?", ticket, generation);
    }
}
