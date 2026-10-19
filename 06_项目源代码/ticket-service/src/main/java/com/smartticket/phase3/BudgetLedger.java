package com.smartticket.phase3;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

@Service
public class BudgetLedger {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Phase3Properties config;

    public BudgetLedger(
            JdbcTemplate db,
            org.springframework.transaction.PlatformTransactionManager tm,
            Phase3Properties config) {
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.config = config;
    }

    public void request(long actor) {
        change(actor, 1, 0);
    }

    public void reserve(long actor, int tokens) {
        change(actor, 0, tokens);
    }

    private void change(long actor, int requests, int tokens) {
        tx.executeWithoutResult(
                s -> {
                    var date = LocalDate.now();
                    db.update(
                            "INSERT IGNORE INTO ai_usage_budget(actor_id,usage_date) VALUES(?,?)",
                            actor,
                            date);
                    int n =
                            db.update(
                                    "UPDATE ai_usage_budget SET"
                                        + " requests_used=requests_used+?,tokens_used=tokens_used+?"
                                        + " WHERE actor_id=? AND usage_date=? AND"
                                        + " requests_used+?<=? AND tokens_used+?<=?",
                                    requests,
                                    tokens,
                                    actor,
                                    date,
                                    requests,
                                    config.getDailyRequests(),
                                    tokens,
                                    config.getDailyTokens());
                    if (n != 1) throw new ApiFailure(429, 42900, "当日AI调用或Token预算已用尽");
                });
    }
}
