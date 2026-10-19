-- Review draft. Select the intended schema in the client before execution.
-- MySQL 8.0.16+; no USE, DROP, TRUNCATE, or business-data updates.
-- Run one migration worker only. DDL steps are not one rollbackable transaction.
SELECT DATABASE() AS migration_schema;

SET @has_ticket_version = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ticket' AND column_name = 'version'
);
SET @ticket_version_ddl = IF(@has_ticket_version = 0,
    'ALTER TABLE ticket ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT ''Optimistic version''',
    'SELECT ''ticket.version already exists; verify its definition'' AS migration_note');
PREPARE ticket_version_statement FROM @ticket_version_ddl;
EXECUTE ticket_version_statement;
DEALLOCATE PREPARE ticket_version_statement;

CREATE TABLE IF NOT EXISTS ai_analysis_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_id BIGINT UNSIGNED NOT NULL,
    input_version BIGINT NOT NULL,
    generation INT NOT NULL DEFAULT 0,
    replay_of_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NULL,
    lease_until DATETIME(3) NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    trace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    error_code VARCHAR(64) NULL,
    error_summary VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_task_id (task_id),
    UNIQUE KEY uk_ai_task_generation (ticket_id, input_version, generation),
    KEY idx_ai_task_retry (status, next_retry_at, id),
    KEY idx_ai_task_lease (status, lease_until, id),
    CONSTRAINT ck_ai_task_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','DEGRADED','FAILED','STALE')),
    CONSTRAINT ck_ai_task_counters CHECK (input_version >= 0 AND generation >= 0 AND attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ai_analysis_result (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_version BIGINT NOT NULL,
    category VARCHAR(50) NULL,
    priority VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    confidence DECIMAL(6,5) NULL,
    keywords JSON NULL,
    reason TEXT NULL,
    source_type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model VARCHAR(100) NULL,
    prompt_version VARCHAR(64) NULL,
    cost_ms BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_result_task (task_id),
    CONSTRAINT ck_ai_result_source CHECK (source_type IN ('MODEL','RULE')),
    CONSTRAINT ck_ai_result_priority CHECK (priority IS NULL OR priority IN ('LOW','MEDIUM','HIGH','URGENT')),
    CONSTRAINT ck_ai_result_confidence CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_ai_result_counters CHECK (input_version >= 0 AND cost_ms >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS outbox_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dispatch_sequence INT NOT NULL DEFAULT 0,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NULL,
    lease_until DATETIME(3) NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_summary VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (event_id),
    UNIQUE KEY uk_outbox_dispatch (task_id, dispatch_sequence),
    KEY idx_outbox_retry (status, next_attempt_at, id),
    KEY idx_outbox_lease (status, lease_until, id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','SENDING','SENT','FAILED')),
    CONSTRAINT ck_outbox_counters CHECK (dispatch_sequence >= 0 AND attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- IF NOT EXISTS does not reconcile an existing table with a different schema.
-- Review SHOW CREATE TABLE and indexes after initial execution and every rerun.
