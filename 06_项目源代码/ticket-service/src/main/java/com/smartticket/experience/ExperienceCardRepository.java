package com.smartticket.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.common.JsonCodec;
import com.smartticket.knowledge.KnowledgeSource;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.smartticket.experience.ExperienceCardContract.*;

@Repository
public class ExperienceCardRepository {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final JsonCodec json;
    public ExperienceCardRepository(JdbcTemplate db,ObjectMapper mapper,JsonCodec json){this.db=db;this.mapper=mapper;this.json=json;}
    private Submission submission(Map<String,Object> r) {
        return new Submission((String)r.get("submission_id"),((Number)r.get("ticket_id")).longValue(),
                ((Number)r.get("ticket_version")).longValue(),((Number)r.get("author_id")).longValue(),(String)r.get("card_id"),(String)r.get("request_hash"),
                Boolean.TRUE.equals(r.get("duplicate_card"))||(r.get("duplicate_card") instanceof Number n&&n.intValue()!=0));
    }
    public Optional<Submission> request(long ticket,long actor,String request) {
        return db.queryForList("SELECT * FROM experience_submission WHERE ticket_id=? AND author_id=? AND request_id=?",ticket,actor,request)
                .stream().map(this::submission).findFirst();
    }
    public Submission submission(String id) {
        var rows=db.queryForList("SELECT * FROM experience_submission WHERE submission_id=?",id);
        if(rows.isEmpty()) throw new com.smartticket.exception.ApiFailure(404,40400,"经验提交不存在");
        return submission(rows.get(0));
    }
    public StoredCard card(String id) {
        var r=db.queryForMap("SELECT * FROM experience_card WHERE card_id=?",id);
        try{return new StoredCard(id,(String)r.get("content_hash"),mapper.readValue((String)r.get("card_json"),Card.class),
                (String)r.get("question"),(String)r.get("answer"));}
        catch(Exception corrupt){throw new IllegalStateException("Invalid stored experience card",corrupt);}
    }
    public record Inserted(StoredCard card,boolean duplicate) {}
    public Inserted ensureCard(Card card,String hash,String answer) {
        String proposed=UUID.randomUUID().toString();
        db.update("INSERT INTO experience_card(card_id,schema_version,content_hash,problem_type,card_json,question,answer)"
                +" VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE card_id=card_id",proposed,ExperienceCardCodec.SCHEMA,hash,card.problemType(),json.encode(card),card.problemType(),answer);
        String id=db.queryForObject("SELECT card_id FROM experience_card WHERE scope='SHARED_DEMO' AND content_hash=? FOR UPDATE",String.class,hash);
        StoredCard stored=card(id);
        if(!stored.content().equals(card)||!stored.answer().equals(answer)) throw new IllegalStateException("Card hash collision or schema mismatch");
        return new Inserted(stored,!proposed.equals(id));
    }
    public Submission insert(long ticket,long version,long actor,String role,Prepare input,String hash,String card,boolean duplicate,Object evidence) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO experience_submission(submission_id,ticket_id,ticket_version,author_id,author_role,request_id,request_hash,card_id,duplicate_card,evidence_snapshot)"
                +" VALUES(?,?,?,?,?,?,?,?,?,?)",id,ticket,version,actor,role,input.requestId(),hash,card,duplicate,json.encode(evidence));
        return submission(id);
    }
    public List<Long> positives(String submission) {
        try {
            String snapshot=db.queryForObject("SELECT evidence_snapshot FROM experience_submission WHERE submission_id=?",String.class,submission);
            var tree=mapper.readTree(snapshot).path("positiveInteractionIds");
            List<Long> ids=new ArrayList<>();for(var node:tree)ids.add(node.longValue());return List.copyOf(ids);
        } catch(Exception corrupt){throw new IllegalStateException("Invalid stored submission evidence",corrupt);}
    }
    public Object snapshot(String submission) {
        try{return mapper.readTree(db.queryForObject("SELECT evidence_snapshot FROM experience_submission WHERE submission_id=?",String.class,submission));}
        catch(Exception corrupt){throw new IllegalStateException("Invalid stored submission evidence",corrupt);}
    }
    public List<Map<String,Object>> feedback(long ticket,long note) {
        return db.queryForList("SELECT id,interaction_id,outcome,origin,comment,correction_interaction_id,author_id,created_at FROM experience_feedback"
                +" WHERE ticket_id=? AND interaction_id=? ORDER BY id DESC LIMIT 20",ticket,note);
    }
    public boolean failed(long ticket,long note) {
        return !db.queryForList("SELECT id FROM experience_feedback WHERE ticket_id=? AND interaction_id=? AND outcome IN ('FAILED','CORRECTED') LIMIT 1",ticket,note).isEmpty();
    }
    public boolean evidenceHealthy(String submission) {
        var s=submission(submission);var ids=positives(submission);
        return !ids.isEmpty()&&ids.stream().noneMatch(id->failed(s.ticketId(),id));
    }
    public Optional<Version> version(String candidate,long revision) {
        return db.queryForList("SELECT * FROM knowledge_experience_version WHERE candidate_id=? AND revision=?",candidate,revision)
                .stream().map(r->new Version(candidate,revision,(String)r.get("card_id"),(String)r.get("submission_id"),
                        (String)r.get("card_hash"),(String)r.get("publication_hash"),(String)r.get("state"))).findFirst();
    }
    public boolean hasVersions(String candidate) {
        return !db.queryForList("SELECT candidate_id FROM knowledge_experience_version WHERE candidate_id=? LIMIT 1",candidate).isEmpty();
    }
    public Version bind(String candidate,long revision,String state,Submission submission,StoredCard card,long actor) {
        String publication=KnowledgeSource.hash(KnowledgeSource.content(card.question(),card.answer()));
        db.update("INSERT INTO knowledge_experience_version(candidate_id,revision,card_id,submission_id,card_hash,publication_hash,state,author_id) VALUES(?,?,?,?,?,?,?,?)",
                candidate,revision,card.id(),submission.id(),card.hash(),publication,state,actor);
        return version(candidate,revision).orElseThrow();
    }
    public Reuse reusable(String card) {
        var rows=db.queryForList("SELECT c.candidate_id,c.revision,v.submission_id FROM knowledge_experience_version v JOIN knowledge_candidate c"
                +" ON c.candidate_id=v.candidate_id AND c.revision=v.revision JOIN ticket_resolution r ON r.resolution_id=c.resolution_id"
                +" JOIN ticket t ON t.id=r.ticket_id WHERE v.card_id=? AND c.state='APPROVED' AND c.indexed_revision=c.revision"
                +" AND c.content_hash=v.publication_hash AND t.deleted=0 ORDER BY c.candidate_id LIMIT 20",card);
        for(var r:rows)if(evidenceHealthy((String)r.get("submission_id"))) {
            String id=(String)r.get("candidate_id");long revision=((Number)r.get("revision")).longValue();
            return new Reuse(id,revision,new KnowledgeSource(id,revision).file());
        }
        return null;
    }
    public List<StoredCard> recent() {
        return db.queryForList("SELECT card_id FROM experience_card ORDER BY created_at DESC,card_id LIMIT 50")
                .stream().map(r->card((String)r.get("card_id"))).toList();
    }
    public List<Map<String,Object>> ticketSubmissions(long ticket,int limit) {
        return db.queryForList("SELECT submission_id AS submissionId,card_id AS cardId,ticket_version AS ticketVersion,author_id AS authorId"
                +" FROM experience_submission WHERE ticket_id=? ORDER BY created_at DESC,submission_id LIMIT ?",ticket,limit);
    }
}
