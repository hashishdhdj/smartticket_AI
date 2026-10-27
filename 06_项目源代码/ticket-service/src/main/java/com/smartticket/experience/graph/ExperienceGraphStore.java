package com.smartticket.experience.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.knowledge.KnowledgeSource;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.smartticket.experience.graph.ExperienceGraph.*;

/** Uses the vector repository's PG connection/transaction; never opens another connection pool. */
@Component
public class ExperienceGraphStore {
    private final ObjectMapper json;
    public ExperienceGraphStore(ObjectMapper json){this.json=json;}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private void fence(JdbcTemplate pg,KnowledgeSource source,String state,String hash) {
        if(!TransactionSynchronizationManager.hasResource(pg.getDataSource()))throw new IllegalStateException("Graph requires the PG publication transaction");
        var rows=pg.queryForList("SELECT revision,state,content_hash FROM knowledge_publication WHERE candidate_id=CAST(? AS uuid) FOR UPDATE",source.candidateId());
        if(rows.size()!=1||((Number)rows.get(0).get("revision")).longValue()!=source.revision()
                ||!state.equals(rows.get(0).get("state"))||!Objects.equals(hash,rows.get(0).get("content_hash")))
            throw new IllegalStateException("Graph cannot cross publication fence");
    }
    public void replace(JdbcTemplate pg,KnowledgeSource source,String textHash,Plan plan) {
        fence(pg,source,"ACTIVE",textHash);
        if(plan!=null&&!plan.textHash().equals(textHash))throw new IllegalStateException("Graph/text hash mismatch");
        pg.update("DELETE FROM experience_edge_evidence WHERE candidate_id=CAST(? AS uuid)",source.candidateId());
        manifest(pg,source,plan==null?"TEXT_ONLY":"ACTIVE",plan);
        if(plan==null)return;
        for(var n:plan.nodes()) {
            int count=pg.update("INSERT INTO experience_node(node_id,scope,kind,node_key,label) VALUES(?,?,?,?,?)"
                    +" ON CONFLICT(node_id) DO UPDATE SET node_id=EXCLUDED.node_id WHERE experience_node.scope=EXCLUDED.scope"
                    +" AND experience_node.kind=EXCLUDED.kind AND experience_node.node_key=EXCLUDED.node_key AND experience_node.label=EXCLUDED.label",
                    n.id(),SCOPE,n.kind().name(),n.key(),n.label());
            if(count!=1)throw new IllegalStateException("Graph node identity conflict");
        }
        for(var e:plan.edges()) {
            int count=pg.update("INSERT INTO experience_edge(edge_id,from_node,relation,to_node,scope) VALUES(?,?,?,?,?)"
                    +" ON CONFLICT(edge_id) DO UPDATE SET edge_id=EXCLUDED.edge_id WHERE experience_edge.from_node=EXCLUDED.from_node"
                    +" AND experience_edge.relation=EXCLUDED.relation AND experience_edge.to_node=EXCLUDED.to_node AND experience_edge.scope=EXCLUDED.scope",
                    e.id(),e.from(),e.relation().name(),e.to(),SCOPE);
            if(count!=1)throw new IllegalStateException("Graph edge identity conflict");
        }
        for(var e:plan.evidence())pg.update("INSERT INTO experience_edge_evidence(candidate_id,revision,edge_id,evidence_key,source_file,card_hash,text_hash,graph_hash,details)"
                +" VALUES(CAST(? AS uuid),?,?,?,?,?,?,?,CAST(? AS jsonb))",source.candidateId(),source.revision(),e.edgeId(),e.key(),source.file(),
                plan.cardHash(),plan.textHash(),plan.graphHash(),encode(e.details()));
    }
    public void retract(JdbcTemplate pg,KnowledgeSource source) {
        fence(pg,source,"RETRACTED",null);
        pg.update("DELETE FROM experience_edge_evidence WHERE candidate_id=CAST(? AS uuid)",source.candidateId());
        manifest(pg,source,"RETRACTED",null);
    }
    private void manifest(JdbcTemplate pg,KnowledgeSource source,String state,Plan p) {
        pg.update("INSERT INTO experience_graph_publication(candidate_id,revision,source_file,state,card_id,submission_id,card_hash,text_hash,graph_hash,compiler_version,fact_scope,evidence_count)"
                +" VALUES(CAST(? AS uuid),?,?,?,CAST(? AS uuid),CAST(? AS uuid),?,?,?,?,?,?) ON CONFLICT(candidate_id) DO UPDATE SET"
                +" revision=EXCLUDED.revision,source_file=EXCLUDED.source_file,state=EXCLUDED.state,card_id=EXCLUDED.card_id,submission_id=EXCLUDED.submission_id,"
                +"card_hash=EXCLUDED.card_hash,text_hash=EXCLUDED.text_hash,graph_hash=EXCLUDED.graph_hash,compiler_version=EXCLUDED.compiler_version,"
                +"fact_scope=EXCLUDED.fact_scope,evidence_count=EXCLUDED.evidence_count,updated_at=now()",source.candidateId(),source.revision(),source.file(),state,
                p==null?null:p.cardId(),p==null?null:p.submissionId(),p==null?null:p.cardHash(),p==null?null:p.textHash(),p==null?null:p.graphHash(),
                p==null?null:COMPILER,p==null?null:p.factScope(),p==null?0:p.evidence().size());
    }
    public Snapshot read(JdbcTemplate pg,KnowledgeSource source) {
        var rows=pg.queryForList("SELECT * FROM experience_graph_publication WHERE candidate_id=CAST(? AS uuid) AND revision=?",source.candidateId(),source.revision());
        if(rows.isEmpty())return null;var r=rows.get(0);
        var m=new Manifest(source.candidateId(),source.revision(),(String)r.get("source_file"),(String)r.get("state"),
                Objects.toString(r.get("card_id"),null),Objects.toString(r.get("submission_id"),null),(String)r.get("card_hash"),
                (String)r.get("text_hash"),(String)r.get("graph_hash"),(String)r.get("compiler_version"),(String)r.get("fact_scope"),((Number)r.get("evidence_count")).intValue());
        if(!"ACTIVE".equals(m.state()))return new Snapshot(m,List.of(),List.of(),List.of());
        var data=pg.queryForList("SELECT e.*,r.from_node,r.relation,r.to_node,r.scope AS edge_scope,a.kind AS a_kind,a.node_key AS a_key,a.label AS a_label,a.scope AS a_scope,"
                +"b.kind AS b_kind,b.node_key AS b_key,b.label AS b_label,b.scope AS b_scope FROM experience_edge_evidence e"
                +" JOIN experience_edge r ON r.edge_id=e.edge_id JOIN experience_node a ON a.node_id=r.from_node JOIN experience_node b ON b.node_id=r.to_node"
                +" WHERE e.candidate_id=CAST(? AS uuid) AND e.revision=? ORDER BY e.edge_id,e.evidence_key LIMIT 129",source.candidateId(),source.revision());
        var nodes=new TreeMap<String,Node>();var edges=new TreeMap<String,Edge>();var evidence=new ArrayList<Support>();
        if(data.size()>128)throw new IllegalStateException("Graph evidence limit exceeded");
        for(var row:data) {
            if(!source.file().equals(row.get("source_file"))||!Objects.equals(m.cardHash(),row.get("card_hash"))||!Objects.equals(m.textHash(),row.get("text_hash"))
                    ||!Objects.equals(m.graphHash(),row.get("graph_hash"))||!SCOPE.equals(row.get("edge_scope"))||!SCOPE.equals(row.get("a_scope"))||!SCOPE.equals(row.get("b_scope")))
                throw new IllegalStateException("Graph row provenance mismatch");
            String from=(String)row.get("from_node"),to=(String)row.get("to_node"),edge=(String)row.get("edge_id");
            nodes.put(from,new Node(from,Kind.valueOf((String)row.get("a_kind")),(String)row.get("a_key"),(String)row.get("a_label")));
            nodes.put(to,new Node(to,Kind.valueOf((String)row.get("b_kind")),(String)row.get("b_key"),(String)row.get("b_label")));
            edges.put(edge,new Edge(edge,from,Relation.valueOf((String)row.get("relation")),to));
            try{evidence.add(new Support(edge,(String)row.get("evidence_key"),json.readValue(row.get("details").toString(),Details.class)));}
            catch(Exception invalid){throw new IllegalStateException("Invalid graph evidence",invalid);}
        }
        return new Snapshot(m,List.copyOf(nodes.values()),List.copyOf(edges.values()),List.copyOf(evidence));
    }
    public boolean matches(JdbcTemplate pg,KnowledgeSource source,Plan p) {
        if(p==null)return true;
        try {
            Snapshot s=read(pg,source);if(s==null)return false;var m=s.publication();
            return "ACTIVE".equals(m.state())&&source.file().equals(m.sourceFile())&&p.cardId().equals(m.cardId())&&p.submissionId().equals(m.submissionId())
                    &&p.cardHash().equals(m.cardHash())&&p.textHash().equals(m.textHash())&&p.graphHash().equals(m.graphHash())&&COMPILER.equals(m.compilerVersion())
                    &&p.factScope().equals(m.factScope())&&m.evidenceCount()==p.evidence().size()&&p.nodes().equals(s.nodes())&&p.edges().equals(s.edges())&&p.evidence().equals(s.evidence());
        }catch(IllegalStateException corrupt){return false;}
    }
}
