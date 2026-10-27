-- G2: immutable normalized cards; evidence stays scoped to its contributing ticket.
USE smartticket;
CREATE TABLE IF NOT EXISTS experience_card (
 card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 schema_version VARCHAR(32) NOT NULL,
 content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 scope VARCHAR(30) NOT NULL DEFAULT 'SHARED_DEMO',
 problem_type VARCHAR(120) NOT NULL,
 card_json JSON NOT NULL,
 question VARCHAR(500) NOT NULL, answer TEXT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_experience_content(scope,content_hash),
 CHECK(scope='SHARED_DEMO')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS experience_submission (
 submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 ticket_id BIGINT UNSIGNED NOT NULL, ticket_version BIGINT NOT NULL,
 author_id BIGINT UNSIGNED NOT NULL, author_role VARCHAR(16) NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 duplicate_card BOOLEAN NOT NULL,
 evidence_snapshot JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_experience_submission_request(ticket_id,author_id,request_id),
 KEY idx_experience_submission_ticket(ticket_id,created_at),
 CONSTRAINT fk_experience_submission_ticket FOREIGN KEY(ticket_id) REFERENCES ticket(id) ON DELETE CASCADE,
 CONSTRAINT fk_experience_submission_card FOREIGN KEY(card_id) REFERENCES experience_card(card_id),
 CHECK(author_role IN ('HANDLER','ADMIN')), CHECK(ticket_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_experience_version (
 candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 card_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 card_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 publication_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(20) NOT NULL,
 author_id BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(candidate_id,revision),
 KEY idx_experience_version_card(card_id),
 CONSTRAINT fk_experience_version_candidate FOREIGN KEY(candidate_id) REFERENCES knowledge_candidate(candidate_id) ON DELETE CASCADE,
 CONSTRAINT fk_experience_version_card FOREIGN KEY(card_id) REFERENCES experience_card(card_id),
 CONSTRAINT fk_experience_version_submission FOREIGN KEY(submission_id) REFERENCES experience_submission(submission_id) ON DELETE CASCADE,
 CHECK(revision>=0), CHECK(state IN ('DRAFT','APPROVED','REJECTED','RETRACTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
