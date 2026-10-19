package com.smartticket.phase3;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CacheRecovery {
    private final Phase3Repository repo;
    private final StringRedisTemplate redis;
    private final Phase3Metrics metrics;

    public CacheRecovery(Phase3Repository repo, StringRedisTemplate redis, Phase3Metrics metrics) {
        this.repo = repo;
        this.redis = redis;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${smartticket.phase3.scheduler-delay-ms:5000}")
    public void recover() {
        try {
            for (var row :
                    repo.db()
                            .queryForList(
                                    "SELECT ticket_id,generation FROM cache_invalidation ORDER BY"
                                            + " updated_at LIMIT 50")) {
                redis.delete("ticket:detail:" + row.get("ticket_id"));
                repo.db()
                        .update(
                                "DELETE FROM cache_invalidation WHERE ticket_id=? AND generation=?",
                                row.get("ticket_id"),
                                row.get("generation"));
                metrics.count("cache.recovery", "success");
            }
        } catch (Exception e) {
            metrics.count("cache.recovery", "retry");
        }
    }
}
