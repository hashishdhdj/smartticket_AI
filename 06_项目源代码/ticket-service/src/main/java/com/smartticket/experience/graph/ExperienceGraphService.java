package com.smartticket.experience.graph;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartticket.entity.Ticket;
import com.smartticket.exception.ApiFailure;
import com.smartticket.experience.ExperienceProperties;
import com.smartticket.knowledge.*;
import com.smartticket.mapper.TicketMapper;
import com.smartticket.rag.*;
import com.smartticket.security.ActorContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.smartticket.experience.graph.ExperienceGraph.*;

/** G3 admin inspection/repair, not an Agent tool or a global graph browsing API. */
@Service
public class ExperienceGraphService {
    private final ExperienceProperties config;
    private final KnowledgeWorkflowProperties workflow;
    private final KnowledgeWorkflowRepository knowledge;
    private final TicketMapper tickets;
    private final ExperienceGraphSource source;
    private final ExperienceGraphReceiptRepository receipts;
    private final KnowledgeVectorRepository vectors;
    private final EmbeddingService embeddings;
    private final JdbcTemplate db;
    public ExperienceGraphService(ExperienceProperties config,KnowledgeWorkflowProperties workflow,KnowledgeWorkflowRepository knowledge,
            TicketMapper tickets,ExperienceGraphSource source,ExperienceGraphReceiptRepository receipts,KnowledgeVectorRepository vectors,
            EmbeddingService embeddings,JdbcTemplate db) {
        this.config=config;this.workflow=workflow;this.knowledge=knowledge;this.tickets=tickets;this.source=source;
        this.receipts=receipts;this.vectors=vectors;this.embeddings=embeddings;this.db=db;
    }
    private String authorize(String id) {
        if(!ActorContext.require().admin())throw ApiFailure.forbidden();
        if(!config.isGraphIndexEnabled()||!workflow.isEnabled())throw new ApiFailure(404,40400,"图谱索引未启用");
        try{if(UUID.fromString(id).toString().equals(id))return id;}catch(Exception ignored){}
        throw new ApiFailure(400,40000,"需要规范小写UUID");
    }
    private KnowledgeWorkflowRepository.Candidate candidate(String id,boolean lock) {
        var initial=knowledge.candidate(authorize(id),false);
        var query=Wrappers.<Ticket>lambdaQuery().eq(Ticket::getId,initial.ticketId());
        if(lock)query.last("FOR UPDATE");
        if(tickets.selectOne(query)==null)throw new ApiFailure(404,40400,"工单不存在");
        return lock?knowledge.candidate(id,true):initial;
    }
    public Map<String,Object> inspect(String id) {
        var c=candidate(id,false);
        var hidden=Map.<String,Object>of("status","NOT_VISIBLE","candidateId",id,"revision",c.revision(),"nodes",List.of(),"edges",List.of(),"evidence",List.of());
        if(!"APPROVED".equals(c.state())||!Objects.equals(c.indexedRevision(),c.revision()))return hidden;
        var key=new KnowledgeSource(id,c.revision());
        if(!knowledge.visibleSources(Set.of(id)).contains(key.file()))return hidden;
        String hash=KnowledgeSource.hash(KnowledgeSource.content(c.question(),c.answer()));
        Plan plan=source.plan(key,c.question(),c.answer(),hash).orElse(null);
        if(plan==null)return Map.of("status","NOT_EXPERIENCE","nodes",List.of(),"edges",List.of(),"evidence",List.of());
        if(!ready(key,plan))return Map.of("status","NOT_READY","candidateId",id,"revision",c.revision(),"nodes",List.of(),"edges",List.of(),"evidence",List.of());
        Snapshot graph=vectors.graph(key);
        // No graph facts leave the API if publication/feedback changed during inspection.
        if(!knowledge.visibleSources(Set.of(id)).contains(key.file())||graph==null)return hidden;
        return Map.of("status","READY","candidateId",id,"revision",c.revision(),"sourceFile",key.file(),"publication",graph.publication(),
                "nodes",graph.nodes(),"edges",graph.edges(),"evidence",graph.evidence(),"receiptHistory",receipts.history(id));
    }
    private boolean ready(KnowledgeSource key,Plan plan) {
        return receipts.matches(key,plan)&&vectors.publicationMatches(key,plan.textHash(),embeddings.modelName())&&vectors.graphMatches(key,plan);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> rebuild(String id,long expectedRevision) {
        var c=candidate(id,true);
        if(expectedRevision<1||c.revision()!=expectedRevision||!"APPROVED".equals(c.state())||!Objects.equals(c.indexedRevision(),c.revision()))throw ApiFailure.conflict();
        var key=new KnowledgeSource(id,c.revision());
        if(!knowledge.visibleSources(Set.of(id)).contains(key.file()))throw ApiFailure.conflict();
        String hash=KnowledgeSource.hash(KnowledgeSource.content(c.question(),c.answer()));
        var plan=source.plan(key,c.question(),c.answer(),hash).orElseThrow(()->new ApiFailure(400,40000,"候选没有结构化经验，不能凭空构图"));
        if(ready(key,plan))return Map.of("status","ALREADY_CURRENT","candidateId",id,"revision",c.revision());
        String job=db.queryForObject("SELECT job_id FROM knowledge_job WHERE resolution_id=? AND kind='INDEX' AND revision=?",String.class,id,c.revision());
        var row=knowledge.jobRow(job,true);
        if(Set.of("PENDING","RUNNING").contains(row.get("status")))return Map.of("status","ALREADY_PENDING","jobId",job,"revision",c.revision());
        knowledge.audit(id,c.revision(),ActorContext.require().id(),"RETRY",hash);
        knowledge.rebuildGraphIndex(job,c.revision(),ActorContext.require().id());
        return Map.of("status","QUEUED","jobId",job,"revision",c.revision());
    }
}
