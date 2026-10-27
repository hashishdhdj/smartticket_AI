package com.smartticket.experience;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.common.JsonCodec;
import com.smartticket.security.ActorContext.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.smartticket.experience.ExperienceContract.*;

@Repository
public class ExperienceRepository {
    public record Stored<T>(T record,String hash) {}
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final JsonCodec json;
    public ExperienceRepository(JdbcTemplate db,ObjectMapper mapper,JsonCodec json) {
        this.db=db;this.mapper=mapper;this.json=json;
    }
    private Interaction interaction(ResultSet r,int row) throws SQLException {
        List<Evidence> refs;
        try { refs=mapper.readValue(r.getString("evidence_json"),new TypeReference<List<Evidence>>(){}); }
        catch(Exception corrupt){throw new IllegalStateException("Invalid stored experience evidence",corrupt);}
        return new Interaction(r.getLong("id"),r.getLong("ticket_id"),r.getString("request_id"),
                r.getLong("ticket_version"),r.getLong("author_id"),r.getString("author_role"),
                Kind.valueOf(r.getString("kind")),r.getString("content"),Origin.valueOf(r.getString("origin")),
                r.getObject("corrects_interaction_id",Long.class),List.copyOf(refs),r.getTimestamp("created_at").toLocalDateTime());
    }
    private Feedback feedback(ResultSet r,int row) throws SQLException {
        return new Feedback(r.getLong("id"),r.getLong("ticket_id"),r.getString("request_id"),
                r.getLong("ticket_version"),r.getLong("author_id"),r.getString("author_role"),r.getLong("interaction_id"),
                Outcome.valueOf(r.getString("outcome")),FeedbackOrigin.valueOf(r.getString("origin")),r.getString("comment"),
                r.getObject("correction_interaction_id",Long.class),r.getTimestamp("created_at").toLocalDateTime());
    }
    public Optional<Stored<Interaction>> interactionRequest(long ticket,long actor,String request) {
        return db.query("SELECT * FROM ticket_interaction WHERE ticket_id=? AND author_id=? AND request_id=?",
                (r,n)->new Stored<>(interaction(r,n),r.getString("request_hash")),ticket,actor,request).stream().findFirst();
    }
    public Optional<Stored<Feedback>> feedbackRequest(long ticket,long actor,String request) {
        return db.query("SELECT * FROM experience_feedback WHERE ticket_id=? AND author_id=? AND request_id=?",
                (r,n)->new Stored<>(feedback(r,n),r.getString("request_hash")),ticket,actor,request).stream().findFirst();
    }
    public Optional<Interaction> interaction(long ticket,long id) {
        return db.query("SELECT * FROM ticket_interaction WHERE ticket_id=? AND id=?",this::interaction,ticket,id).stream().findFirst();
    }
    public Interaction append(long ticket,Actor actor,InteractionInput body,String hash) {
        db.update("INSERT INTO ticket_interaction(ticket_id,request_id,request_hash,ticket_version,author_id,author_role,"
                +"kind,content,origin,corrects_interaction_id,evidence_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                ticket,body.requestId(),hash,body.expectedVersion(),actor.id(),actor.role(),body.kind().name(),
                body.content(),body.origin().name(),body.correctsInteractionId(),json.encode(body.evidenceRefs()));
        return interactionRequest(ticket,actor.id(),body.requestId()).orElseThrow().record();
    }
    public Feedback append(long ticket,Actor actor,FeedbackInput body,String hash) {
        db.update("INSERT INTO experience_feedback(ticket_id,request_id,request_hash,ticket_version,author_id,author_role,"
                +"interaction_id,outcome,origin,comment,correction_interaction_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                ticket,body.requestId(),hash,body.expectedVersion(),actor.id(),actor.role(),body.interactionId(),
                body.outcome().name(),body.origin().name(),body.comment(),body.correctionInteractionId());
        return feedbackRequest(ticket,actor.id(),body.requestId()).orElseThrow().record();
    }
    public List<Interaction> interactions(long ticket,long after,int limit) {
        return db.query("SELECT * FROM ticket_interaction WHERE ticket_id=? AND id>? ORDER BY id LIMIT ?",
                this::interaction,ticket,after,limit);
    }
    public List<Feedback> feedback(long ticket,long after,int limit) {
        return db.query("SELECT * FROM experience_feedback WHERE ticket_id=? AND id>? ORDER BY id LIMIT ?",
                this::feedback,ticket,after,limit);
    }
}
