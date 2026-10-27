-- G3: concept vocabulary is shared; claims always require active, versioned evidence.
CREATE TABLE IF NOT EXISTS experience_node (
 node_id CHAR(64) PRIMARY KEY,
 scope VARCHAR(30) NOT NULL CHECK(scope='SHARED_DEMO'),
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('PROBLEM','SYMPTOM','CONDITION','STEP','OUTCOME','COUNTEREXAMPLE','HYPOTHESIS')),
 node_key TEXT NOT NULL CHECK(length(node_key) BETWEEN 1 AND 500),
 label TEXT NOT NULL,
 UNIQUE(scope,kind,node_key)
);
CREATE TABLE IF NOT EXISTS experience_edge (
 edge_id CHAR(64) PRIMARY KEY,
 from_node CHAR(64) NOT NULL REFERENCES experience_node(node_id),
 relation VARCHAR(32) NOT NULL CHECK(relation IN ('HAS_SYMPTOM','REQUIRES','ADDRESSED_BY','HAS_OUTCOME','CONTRAINDICATED','HAS_COUNTEREXAMPLE','HAS_HYPOTHESIS','NEXT_STEP')),
 to_node CHAR(64) NOT NULL REFERENCES experience_node(node_id),
 scope VARCHAR(30) NOT NULL CHECK(scope='SHARED_DEMO'),
 UNIQUE(scope,from_node,relation,to_node)
);
CREATE TABLE IF NOT EXISTS experience_graph_publication (
 candidate_id UUID PRIMARY KEY REFERENCES knowledge_publication(candidate_id) ON DELETE CASCADE,
 revision BIGINT NOT NULL CHECK(revision>0),
 source_file TEXT NOT NULL,
 state VARCHAR(12) NOT NULL CHECK(state IN ('ACTIVE','RETRACTED','TEXT_ONLY')),
 card_id UUID, submission_id UUID,
 card_hash CHAR(64), text_hash CHAR(64), graph_hash CHAR(64),
 compiler_version VARCHAR(40), fact_scope VARCHAR(80),
 evidence_count INTEGER NOT NULL DEFAULT 0 CHECK(evidence_count>=0),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(candidate_id,revision),
 CHECK(state<>'ACTIVE' OR (card_id IS NOT NULL AND submission_id IS NOT NULL AND card_hash IS NOT NULL
     AND text_hash IS NOT NULL AND graph_hash IS NOT NULL AND compiler_version IS NOT NULL AND evidence_count>0))
);
CREATE TABLE IF NOT EXISTS experience_edge_evidence (
 candidate_id UUID NOT NULL,
 revision BIGINT NOT NULL,
 edge_id CHAR(64) NOT NULL REFERENCES experience_edge(edge_id),
 evidence_key VARCHAR(64) NOT NULL,
 source_file TEXT NOT NULL,
 card_hash CHAR(64) NOT NULL, text_hash CHAR(64) NOT NULL, graph_hash CHAR(64) NOT NULL,
 details JSONB NOT NULL,
 PRIMARY KEY(candidate_id,revision,edge_id,evidence_key),
 FOREIGN KEY(candidate_id,revision) REFERENCES experience_graph_publication(candidate_id,revision) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_experience_edge_from ON experience_edge(from_node,relation);
CREATE INDEX IF NOT EXISTS idx_experience_edge_to ON experience_edge(to_node,relation);
CREATE INDEX IF NOT EXISTS idx_experience_evidence_edge ON experience_edge_evidence(edge_id,candidate_id,revision);
