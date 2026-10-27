package com.smartticket.experience.graph;

import com.smartticket.knowledge.KnowledgeSource;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.smartticket.experience.graph.ExperienceGraph.*;

@Repository
public class ExperienceGraphReceiptRepository {
    private final JdbcTemplate db;
    public ExperienceGraphReceiptRepository(JdbcTemplate db){this.db=db;}
    public void record(KnowledgeSource source,Plan p) {
        if(!TransactionSynchronizationManager.hasResource(db.getDataSource()))throw new IllegalStateException("Graph receipt requires the MySQL acknowledgement transaction");
        db.update("INSERT INTO knowledge_graph_receipt(candidate_id,revision,compiler_version,card_hash,text_hash,graph_hash,node_count,edge_count,evidence_count)"
                +" VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE candidate_id=candidate_id",source.candidateId(),source.revision(),COMPILER,
                p.cardHash(),p.textHash(),p.graphHash(),p.nodes().size(),p.edges().size(),p.evidence().size());
        if(!matches(source,p))throw new IllegalStateException("Conflicting graph receipt");
    }
    public boolean matches(KnowledgeSource source,Plan p) {
        return !db.queryForList("SELECT candidate_id FROM knowledge_graph_receipt WHERE candidate_id=? AND revision=? AND compiler_version=?"
                +" AND card_hash=? AND text_hash=? AND graph_hash=? AND node_count=? AND edge_count=? AND evidence_count=?",source.candidateId(),
                source.revision(),COMPILER,p.cardHash(),p.textHash(),p.graphHash(),p.nodes().size(),p.edges().size(),p.evidence().size()).isEmpty();
    }
    public List<Map<String,Object>> history(String candidate) {
        return db.queryForList("SELECT revision,compiler_version AS compilerVersion,card_hash AS cardHash,text_hash AS textHash,graph_hash AS graphHash,"
                +"node_count AS nodeCount,edge_count AS edgeCount,evidence_count AS evidenceCount FROM knowledge_graph_receipt"
                +" WHERE candidate_id=? ORDER BY revision,compiler_version LIMIT 50",candidate);
    }
}
