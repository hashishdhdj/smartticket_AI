USE smartticket;
CREATE TABLE IF NOT EXISTS knowledge_graph_receipt (
 candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL,
 compiler_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 card_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 text_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 graph_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 node_count INT NOT NULL, edge_count INT NOT NULL, evidence_count INT NOT NULL,
 indexed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(candidate_id,revision,compiler_version),
 CONSTRAINT fk_graph_receipt_candidate FOREIGN KEY(candidate_id) REFERENCES knowledge_candidate(candidate_id) ON DELETE CASCADE,
 CHECK(revision>0 AND node_count>0 AND edge_count>0 AND evidence_count>0)
) ENGINE=InnoDB;
