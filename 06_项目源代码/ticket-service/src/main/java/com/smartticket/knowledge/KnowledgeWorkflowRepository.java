package com.smartticket.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.common.JsonCodec;
import com.smartticket.entity.Ticket;
import com.smartticket.exception.ApiFailure;
import com.smartticket.mq.KnowledgeJobMessage;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL catalog and job transitions. No broker or embedding I/O inside its transactions. */
@Repository
public class KnowledgeWorkflowRepository {
    public record Candidate(String id, long ticketId, long revision, String state, String question,
                            String answer, String scope, Long indexedRevision, Long reviewerId) {}
    public record Job(String id, String resolutionId, String kind, long revision, String input,
                      int attempts, String token) {}
    public record PublicationInput(String question, String answer, String hash) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final JsonCodec json;
    private final KnowledgeOutboxRepository outbox;
    private final KnowledgeWorkflowProperties config;
    private final com.smartticket.experience.ExperienceKnowledgeBridge experience;
    private final com.smartticket.experience.graph.ExperienceGraphReceiptRepository graphs;

    public KnowledgeWorkflowRepository(JdbcTemplate db, PlatformTransactionManager tm, ObjectMapper mapper,
            JsonCodec json, KnowledgeOutboxRepository outbox, KnowledgeWorkflowProperties config,
            com.smartticket.experience.ExperienceKnowledgeBridge experience,
            com.smartticket.experience.graph.ExperienceGraphReceiptRepository graphs) {
        this.db=db; this.tx=new TransactionTemplate(tm); this.mapper=mapper;
        this.json=json; this.outbox=outbox; this.config=config;
        this.experience=experience;
        this.graphs=graphs;
    }

    public String recordResolution(Ticket ticket, long author, String solution) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Resolution and outbox require the ticket transaction");
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO ticket_resolution(resolution_id,ticket_id,input_version,author_id,title,description,solution)"
                + " VALUES(?,?,?,?,?,?,?)",id,ticket.getId(),ticket.getVersion()+1,author,ticket.getTitle(),ticket.getDescription(),solution);
        createJob(id,"DRAFT",0,Map.of());
        return id;
    }

    public String createJob(String resolution, String kind, long revision, Object input) {
        String job=UUID.randomUUID().toString(), trace=UUID.randomUUID().toString();
        db.update("INSERT INTO knowledge_job(job_id,resolution_id,kind,revision,input_json,trace_id) VALUES(?,?,?,?,?,?)",
                job,resolution,kind,revision,json.encode(input),trace);
        outbox.enqueue(job,resolution,kind,revision,trace,0);
        return job;
    }

    public Candidate candidate(String id, boolean lock) {
        var rows=db.queryForList("SELECT c.*,r.ticket_id FROM knowledge_candidate c JOIN ticket_resolution r"
                + " ON c.resolution_id=r.resolution_id WHERE c.candidate_id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty()) throw new ApiFailure(404,40400,"知识候选不存在");
        var row=rows.get(0);
        return new Candidate(id,((Number)row.get("ticket_id")).longValue(),((Number)row.get("revision")).longValue(),
                (String)row.get("state"),(String)row.get("question"),(String)row.get("answer"),(String)row.get("scope"),
                row.get("indexed_revision")==null?null:((Number)row.get("indexed_revision")).longValue(),
                row.get("reviewer_id")==null?null:((Number)row.get("reviewer_id")).longValue());
    }

    public List<Candidate> candidates(int limit) {
        return db.queryForList("SELECT candidate_id FROM knowledge_candidate ORDER BY created_at DESC,candidate_id LIMIT ?",limit)
                .stream().map(row->candidate((String)row.get("candidate_id"),false)).toList();
    }

    public List<Map<String,Object>> ticketResolutions(long ticket) {
        return db.queryForList("SELECT r.resolution_id AS resolutionId,r.input_version AS inputVersion,"
                + "c.state,c.revision,c.indexed_revision AS indexedRevision FROM ticket_resolution r LEFT JOIN knowledge_candidate c"
                + " ON c.resolution_id=r.resolution_id WHERE r.ticket_id=? ORDER BY r.input_version DESC LIMIT 20",ticket);
    }

    public List<Map<String,Object>> jobs(String resolution) {
        return db.queryForList("SELECT job_id AS jobId,kind,revision,status,attempts,total_attempts AS totalAttempts,"
                + "replays,error_code AS errorCode FROM knowledge_job WHERE resolution_id=? ORDER BY created_at,job_id",resolution);
    }

    public Candidate review(Candidate old, String state, String question, String answer, long actor) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Knowledge review requires a transaction");
        experience.review(old,state,question,answer,actor);
        String hash="APPROVED".equals(state)?KnowledgeSource.hash(KnowledgeSource.content(question,answer)):null;
        int count=db.update("UPDATE knowledge_candidate SET revision=revision+1,state=?,question=?,answer=?,reviewer_id=?,"
                + "indexed_revision=NULL,content_hash=? WHERE candidate_id=? AND revision=?",state,question,answer,actor,hash,old.id(),old.revision());
        if(count!=1) throw ApiFailure.conflict();
        String action=switch(state){case "APPROVED"->"APPROVE";case "REJECTED"->"REJECT";default->"RETRACT";};
        audit(old.id(),old.revision()+1,actor,action,hash);
        if("APPROVED".equals(state)) createJob(old.id(),"INDEX",old.revision()+1,new PublicationInput(question,answer,hash));
        if("RETRACTED".equals(state)) createJob(old.id(),"DELETE",old.revision()+1,Map.of());
        return candidate(old.id(),false);
    }

    public void audit(String candidate,long revision,long actor,String action,String hash) {
        db.update("INSERT INTO knowledge_review_audit(candidate_id,revision,actor_id,action,content_hash) VALUES(?,?,?,?,?)",
                candidate,revision,actor,action,hash);
    }

    public Optional<Job> claim(KnowledgeJobMessage message) {
        return tx.execute(status->{
            var rows=db.queryForList("SELECT * FROM knowledge_job WHERE job_id=? FOR UPDATE",message.jobId());
            if(rows.isEmpty()) throw new ApiFailure(400,40000,"未知知识任务");
            var row=rows.get(0);
            var events=db.queryForList("SELECT payload FROM knowledge_outbox WHERE event_id=? AND job_id=?",message.eventId(),message.jobId());
            boolean matches=Objects.equals(row.get("resolution_id"),message.resolutionId())
                    &&Objects.equals(row.get("kind"),message.kind())&&((Number)row.get("revision")).longValue()==message.revision()
                    &&Objects.equals(row.get("trace_id"),message.traceId());
            try { matches=matches&&!events.isEmpty()&&mapper.readValue((String)events.get(0).get("payload"),KnowledgeJobMessage.class).equals(message); }
            catch(Exception invalid){matches=false;}
            if(!matches) throw new ApiFailure(400,40000,"消息与知识任务不匹配");
            if(!Set.of("PENDING","RUNNING").contains(row.get("status"))) return Optional.empty();
            Boolean running=db.queryForObject("SELECT (status='RUNNING' AND lease_until>NOW(3)) OR (next_retry_at>NOW(3))"
                    + " FROM knowledge_job WHERE job_id=?",Boolean.class,message.jobId());
            if(Boolean.TRUE.equals(running)) return Optional.empty();
            int attempts=((Number)row.get("attempts")).intValue();
            if(attempts>=config.getMaxAttempts()) {
                db.update("UPDATE knowledge_job SET status='FAILED',error_code='LEASE_EXHAUSTED',lease_token=NULL,lease_until=NULL WHERE job_id=?",message.jobId());
                return Optional.empty();
            }
            String token=UUID.randomUUID().toString();
            db.update("UPDATE knowledge_job SET status='RUNNING',attempts=attempts+1,total_attempts=total_attempts+1,"
                    + "lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,NOW(3)),next_retry_at=NULL WHERE job_id=?",token,config.getLeaseSeconds(),message.jobId());
            return Optional.of(new Job(message.jobId(),message.resolutionId(),message.kind(),message.revision(),
                    (String)row.get("input_json"),attempts+1,token));
        });
    }

    private boolean owns(Job job) {
        return !db.queryForList("SELECT job_id FROM knowledge_job WHERE job_id=? AND status='RUNNING' AND lease_token=? FOR UPDATE",
                job.id(),job.token()).isEmpty();
    }

    public void completeDraft(Job job) {
        tx.executeWithoutResult(status->{
            if(!owns(job)) return;
            var resolution=db.queryForMap("SELECT title,solution FROM ticket_resolution WHERE resolution_id=?",job.resolutionId());
            db.update("INSERT INTO knowledge_candidate(candidate_id,resolution_id,question,answer) VALUES(?,?,?,?)"
                    + " ON DUPLICATE KEY UPDATE candidate_id=candidate_id",job.resolutionId(),job.resolutionId(),resolution.get("title"),resolution.get("solution"));
            finish(job,"SUCCEEDED",null);
        });
    }

    public boolean current(Job job) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM knowledge_candidate c JOIN ticket_resolution r"
                + " ON c.resolution_id=r.resolution_id JOIN ticket t ON t.id=r.ticket_id WHERE c.candidate_id=? AND c.revision=?"
                + " AND c.state=? AND t.deleted=0)",Boolean.class,job.resolutionId(),job.revision(),"INDEX".equals(job.kind())?"APPROVED":"RETRACTED"));
    }

    public boolean indexed(Job job) {
        return indexed(job,null);
    }

    public boolean indexed(Job job,com.smartticket.experience.graph.ExperienceGraph.Plan graph) {
        return tx.execute(status->{
            Candidate candidate=candidate(job.resolutionId(),true);
            if(!owns(job)) return false;
            if(candidate.revision()!=job.revision()||!"APPROVED".equals(candidate.state())||!current(job)) {
                finish(job,"STALE","INPUT_CHANGED");return false;
            }
            db.update("UPDATE knowledge_candidate SET indexed_revision=? WHERE candidate_id=?",job.revision(),job.resolutionId());
            if(graph!=null)graphs.record(new KnowledgeSource(job.resolutionId(),job.revision()),graph);
            finish(job,"SUCCEEDED",null);return true;
        });
    }

    public void finish(Job job,String state,String error) {
        db.update("UPDATE knowledge_job SET status=?,error_code=?,lease_token=NULL,lease_until=NULL WHERE job_id=?"
                + " AND status='RUNNING' AND lease_token=?",state,error,job.id(),job.token());
    }

    public boolean failed(Job job,String code) {
        return tx.execute(status->{
            if(!owns(job)) return false;
            boolean terminal=job.attempts()>=config.getMaxAttempts();
            db.update("UPDATE knowledge_job SET status=?,error_code=?,lease_token=NULL,lease_until=NULL,"
                    + "next_retry_at=TIMESTAMPADD(SECOND,?,NOW(3)) WHERE job_id=?",terminal?"FAILED":"PENDING",code,
                    job.attempts()==1?5:30,job.id());
            return terminal;
        });
    }

    public void recover() {
        tx.executeWithoutResult(status->{
            var rows=db.queryForList("SELECT * FROM knowledge_job WHERE (status='RUNNING' AND lease_until<NOW(3)) OR"
                    + " (status='PENDING' AND (next_retry_at<=NOW(3) OR (next_retry_at IS NULL AND queued_at<TIMESTAMPADD(SECOND,-?,NOW(3)))))"
                    + " ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED",config.getLeaseSeconds());
            for(var row:rows) {
                String id=(String)row.get("job_id");
                if(((Number)row.get("attempts")).intValue()>=config.getMaxAttempts()) {
                    db.update("UPDATE knowledge_job SET status='FAILED',error_code='LEASE_EXHAUSTED',lease_token=NULL,lease_until=NULL WHERE job_id=?",id);
                } else redispatch(row,false);
            }
        });
    }

    private void redispatch(Map<String,Object> row,boolean replay) {
        int sequence=((Number)row.get("dispatch_sequence")).intValue()+1;
        db.update("UPDATE knowledge_job SET status='PENDING',lease_token=NULL,lease_until=NULL,next_retry_at=NULL,"
                + "queued_at=NOW(3),dispatch_sequence=?,error_code=NULL"+(replay?",attempts=0,replays=replays+1":"")+" WHERE job_id=?",
                sequence,row.get("job_id"));
        outbox.enqueue((String)row.get("job_id"),(String)row.get("resolution_id"),(String)row.get("kind"),
                ((Number)row.get("revision")).longValue(),(String)row.get("trace_id"),sequence);
    }

    public Map<String,Object> jobRow(String id,boolean lock) {
        var rows=db.queryForList("SELECT * FROM knowledge_job WHERE job_id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty()) throw new ApiFailure(404,40400,"知识任务不存在");
        return rows.get(0);
    }

    public void replay(String id,long actor) {
        var row=jobRow(id,true);
        if(!"FAILED".equals(row.get("status"))) throw ApiFailure.conflict();
        replayRow(id,actor,row);
    }

    /** A successful text INDEX may need graph backfill; ordinary failed-job replay stays strict. */
    public void rebuildGraphIndex(String id,long revision,long actor) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Graph rebuild requires a MySQL transaction");
        var row=jobRow(id,true);
        if(!"INDEX".equals(row.get("kind"))||((Number)row.get("revision")).longValue()!=revision
                ||!Set.of("SUCCEEDED","FAILED","STALE").contains(row.get("status")))throw ApiFailure.conflict();
        var candidate=candidate((String)row.get("resolution_id"),true);
        if(candidate.revision()!=revision||!"APPROVED".equals(candidate.state())||!Objects.equals(candidate.indexedRevision(),revision))throw ApiFailure.conflict();
        replayRow(id,actor,row);
    }

    private void replayRow(String id,long actor,Map<String,Object> row) {
        db.update("INSERT INTO knowledge_job_replay_audit(job_id,replay_no,requester_id,attempts_before,total_attempts_before)"
                + " VALUES(?,?,?,?,?)",id,((Number)row.get("replays")).intValue()+1,actor,row.get("attempts"),row.get("total_attempts"));
        redispatch(row,true);
    }

    public Set<String> visibleSources(Set<String> candidateIds) {
        if(candidateIds.isEmpty()) return Set.of();
        String placeholders=String.join(",",Collections.nCopies(candidateIds.size(),"?"));
        var rows=db.queryForList("SELECT c.candidate_id,c.revision FROM knowledge_candidate c JOIN ticket_resolution r"
                + " ON c.resolution_id=r.resolution_id JOIN ticket t ON t.id=r.ticket_id WHERE c.candidate_id IN ("+placeholders+")"
                + " AND c.state='APPROVED' AND c.revision=c.indexed_revision AND t.deleted=0",candidateIds.toArray());
        Set<String> sources=new HashSet<>();
        for(var row:rows) {
            String id=(String)row.get("candidate_id");long revision=((Number)row.get("revision")).longValue();
            if(experience.visible(id,revision))sources.add(new KnowledgeSource(id,revision).file());
        }
        return sources;
    }
}
