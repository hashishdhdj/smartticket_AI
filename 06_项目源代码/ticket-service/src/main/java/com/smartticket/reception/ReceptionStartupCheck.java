package com.smartticket.reception;

import com.smartticket.config.RuntimeProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReceptionStartupCheck implements ApplicationRunner {
    private final ReceptionProperties config;
    private final RuntimeProperties runtime;
    private final JdbcTemplate db;
    public ReceptionStartupCheck(ReceptionProperties config,RuntimeProperties runtime,JdbcTemplate db) {
        this.config=config;this.runtime=runtime;this.db=db;
    }
    @Override public void run(ApplicationArguments args) {
        if(!config.isEnabled())return;
        if(!runtime.isSecurityEnabled())throw new IllegalStateException("Agent reception requires trusted API authentication");
        // Fail activation before accepting work if the migration is missing; no model call or backfill.
        db.queryForList("SELECT ticket_id,state,input_version,handoff_summary FROM ticket_reception WHERE 1=0");
        db.queryForList("SELECT ticket_id,request_key,request_hash,content FROM ticket_reception_message WHERE 1=0");
    }
}
