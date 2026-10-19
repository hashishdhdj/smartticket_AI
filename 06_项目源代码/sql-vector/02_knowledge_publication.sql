-- PG-side fencing. Tombstones prevent a late index job reviving a retracted source.
CREATE TABLE IF NOT EXISTS knowledge_publication (
 candidate_id UUID PRIMARY KEY,
 revision BIGINT NOT NULL CHECK(revision>0),
 source_file TEXT NOT NULL,
 state VARCHAR(12) NOT NULL CHECK(state IN ('ACTIVE','RETRACTED')),
 content_hash CHAR(64),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_knowledge_publication_active
 ON knowledge_publication(source_file) WHERE state='ACTIVE';
