-- G1: append-only internal staff observations. No automatic knowledge publication.
USE smartticket;

CREATE TABLE IF NOT EXISTS ticket_interaction (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ticket_id BIGINT UNSIGNED NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_version BIGINT NOT NULL,
    author_id BIGINT UNSIGNED NOT NULL,
    author_role VARCHAR(16) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    origin VARCHAR(24) NOT NULL,
    corrects_interaction_id BIGINT UNSIGNED NULL,
    evidence_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_interaction_ticket_id (ticket_id,id),
    UNIQUE KEY uk_interaction_request (ticket_id,author_id,request_id),
    CONSTRAINT fk_interaction_ticket FOREIGN KEY (ticket_id) REFERENCES ticket(id) ON DELETE CASCADE,
    CONSTRAINT fk_interaction_correction FOREIGN KEY (ticket_id,corrects_interaction_id)
        REFERENCES ticket_interaction(ticket_id,id) ON DELETE CASCADE,
    CONSTRAINT ck_interaction_role CHECK (author_role IN ('HANDLER','ADMIN')),
    CONSTRAINT ck_interaction_kind CHECK (kind IN ('STAFF_REPLY','TROUBLESHOOTING','CORRECTION')),
    CONSTRAINT ck_interaction_origin CHECK (origin IN ('STAFF_ENTERED','AI_SYNTHETIC_DEMO')),
    CONSTRAINT ck_interaction_version CHECK (ticket_version >= 0),
    CONSTRAINT ck_interaction_correction CHECK ((kind='CORRECTION' AND corrects_interaction_id IS NOT NULL)
        OR (kind<>'CORRECTION' AND corrects_interaction_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS experience_feedback (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ticket_id BIGINT UNSIGNED NOT NULL,
    interaction_id BIGINT UNSIGNED NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_version BIGINT NOT NULL,
    author_id BIGINT UNSIGNED NOT NULL,
    author_role VARCHAR(16) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    origin VARCHAR(24) NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    correction_interaction_id BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_feedback_ticket (ticket_id,id),
    UNIQUE KEY uk_feedback_request (ticket_id,author_id,request_id),
    CONSTRAINT fk_feedback_ticket FOREIGN KEY (ticket_id) REFERENCES ticket(id) ON DELETE CASCADE,
    CONSTRAINT fk_feedback_interaction FOREIGN KEY (ticket_id,interaction_id)
        REFERENCES ticket_interaction(ticket_id,id) ON DELETE CASCADE,
    CONSTRAINT fk_feedback_correction FOREIGN KEY (ticket_id,correction_interaction_id)
        REFERENCES ticket_interaction(ticket_id,id) ON DELETE CASCADE,
    CONSTRAINT ck_feedback_role CHECK (author_role IN ('HANDLER','ADMIN')),
    CONSTRAINT ck_feedback_outcome CHECK (outcome IN ('ADOPTED','EFFECTIVE','FAILED','CORRECTED','UNVERIFIED')),
    CONSTRAINT ck_feedback_origin CHECK (origin IN ('STAFF_OBSERVATION','SIMULATED_DEMO')),
    CONSTRAINT ck_feedback_version CHECK (ticket_version >= 0),
    CONSTRAINT ck_feedback_correction CHECK ((outcome='CORRECTED' AND correction_interaction_id IS NOT NULL)
        OR (outcome<>'CORRECTED' AND correction_interaction_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
