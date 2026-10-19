package com.smartticket.cache;


import com.smartticket.governance.ApplicationMetrics;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CacheRecovery {
    private final CacheInvalidationRepository repo;
    private final StringRedisTemplate redis;
    private final ApplicationMetrics metrics;

    public CacheRecovery(CacheInvalidationRepository repo, StringRedisTemplate redis, ApplicationMetrics metrics) {
        this.repo = repo;
        this.redis = redis;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${smartticket.phase3.scheduler-delay-ms:5000}")
    public void recover() {
        try {
            for (var row : repo.pending()) {
                long ticket = ((Number) row.get("ticket_id")).longValue();
                long generation = ((Number) row.get("generation")).longValue();
                redis.delete("ticket:detail:" + ticket);
                repo.completed(ticket, generation);
                metrics.count("cache.recovery", "success");
            }
        } catch (Exception e) {
            metrics.count("cache.recovery", "retry");
        }
    }
}
