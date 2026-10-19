-- Reviewed knowledge lifecycle. Apply after 01..04; repeatable, no destructive statements.
CREATE TABLE IF NOT EXISTS ticket_resolution (
 resolution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 ticket_id BIGINT UNSIGNED NOT NULL, input_version BIGINT NOT NULL,
 author_id BIGINT UNSIGNED NOT NULL, title VARCHAR(200) NOT NULL,
 description TEXT NULL, solution TEXT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_resolution_version(ticket_id,input_version),
 CONSTRAINT fk_resolution_ticket FOREIGN KEY(ticket_id) REFERENCES ticket(id) ON DELETE CASCADE,
 CHECK(input_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_candidate (
 candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 resolution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 revision BIGINT NOT NULL DEFAULT 0, state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 question VARCHAR(500) NOT NULL, answer TEXT NOT NULL,
 scope VARCHAR(30) NOT NULL DEFAULT 'SHARED_DEMO',
 reviewer_id BIGINT UNSIGNED NULL, indexed_revision BIGINT NULL,
 content_hash CHAR(64) CHARACTER SET ascii NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_candidate_resolution FOREIGN KEY(resolution_id) REFERENCES ticket_resolution(resolution_id) ON DELETE CASCADE,
 KEY idx_candidate_review(state,updated_at),
 CHECK(revision>=0), CHECK(state IN ('DRAFT','APPROVED','REJECTED','RETRACTED')),
 CHECK(scope='SHARED_DEMO')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_job (
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 resolution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(12) NOT NULL, revision BIGINT NOT NULL,
 input_json JSON NOT NULL, trace_id CHAR(36) NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0, total_attempts INT NOT NULL DEFAULT 0,
 replays INT NOT NULL DEFAULT 0, dispatch_sequence INT NOT NULL DEFAULT 0,
 lease_token CHAR(36) NULL, lease_until DATETIME(3) NULL,
 next_retry_at DATETIME(3) NULL, queued_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 error_code VARCHAR(40) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_knowledge_job(resolution_id,kind,revision),
 KEY idx_knowledge_job_recovery(status,next_retry_at,lease_until),
 CONSTRAINT fk_knowledge_job_resolution FOREIGN KEY(resolution_id) REFERENCES ticket_resolution(resolution_id) ON DELETE CASCADE,
 CHECK(kind IN ('DRAFT','INDEX','DELETE')),
 CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','STALE')),
 CHECK(revision>=0 AND attempts>=0 AND total_attempts>=0 AND dispatch_sequence>=0 AND replays>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_outbox (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 dispatch_sequence INT NOT NULL, payload JSON NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 lease_token CHAR(36) NULL, lease_until DATETIME(3) NULL,
 next_attempt_at DATETIME(3) NULL, error_code VARCHAR(40) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_knowledge_dispatch(job_id,dispatch_sequence),
 KEY idx_knowledge_outbox(status,next_attempt_at,lease_until),
 CONSTRAINT fk_knowledge_outbox_job FOREIGN KEY(job_id) REFERENCES knowledge_job(job_id) ON DELETE CASCADE,
 CHECK(status IN ('PENDING','SENDING','SENT')), CHECK(attempts>=0 AND dispatch_sequence>=0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS knowledge_review_audit (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL, actor_id BIGINT UNSIGNED NOT NULL,
 action VARCHAR(12) NOT NULL, content_hash CHAR(64) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_knowledge_review_candidate FOREIGN KEY(candidate_id) REFERENCES knowledge_candidate(candidate_id) ON DELETE CASCADE,
 KEY idx_knowledge_review(candidate_id,revision),
 CHECK(action IN ('APPROVE','REJECT','RETRACT','RETRY'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS knowledge_job_replay_audit (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 replay_no INT NOT NULL, requester_id BIGINT UNSIGNED NOT NULL,
 attempts_before INT NOT NULL, total_attempts_before INT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_knowledge_replay(job_id,replay_no),
 CONSTRAINT fk_knowledge_replay_job FOREIGN KEY(job_id) REFERENCES knowledge_job(job_id) ON DELETE CASCADE
) ENGINE=InnoDB;
