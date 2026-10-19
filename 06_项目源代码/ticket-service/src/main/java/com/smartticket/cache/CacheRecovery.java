package com.smartticket.cache;

import com.smartticket.analysis.persistence.AnalysisTaskRepository;
import com.smartticket.governance.ApplicationMetrics;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CacheRecovery {
    private final AnalysisTaskRepository repo;
    private final StringRedisTemplate redis;
    private final ApplicationMetrics metrics;

    public CacheRecovery(AnalysisTaskRepository repo, StringRedisTemplate redis, ApplicationMetrics metrics) {
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
