-- Apply after 01..08. No historic tickets are enrolled automatically.
CREATE TABLE IF NOT EXISTS ticket_reception (
 ticket_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 state VARCHAR(24) NOT NULL,
 turn_no INT NOT NULL DEFAULT 1,
 input_version BIGINT NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 lease_token CHAR(36) NULL,
 lease_until DATETIME(3) NULL,
 last_run_id CHAR(36) NULL,
 reason VARCHAR(100) NULL,
 handoff_summary TEXT NULL,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY idx_reception_work(state,lease_until,updated_at),
 CHECK (state IN ('QUEUED','RUNNING','WAITING_INPUT','WAITING_CUSTOMER','HUMAN_REQUIRED','HUMAN_ACTIVE','RESOLVED','CLOSED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ticket_reception_message (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 ticket_id BIGINT UNSIGNED NOT NULL,
 turn_no INT NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL,
 kind VARCHAR(12) NOT NULL,
 request_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) NOT NULL,
 content TEXT NOT NULL,
 run_id CHAR(36) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_reception_request(ticket_id,request_key),
 KEY idx_reception_history(ticket_id,id),
 CHECK (kind IN ('CUSTOMER','AGENT','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
