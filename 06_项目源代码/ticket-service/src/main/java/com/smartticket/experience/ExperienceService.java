package com.smartticket.experience;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartticket.common.JsonCodec;
import com.smartticket.entity.Ticket;
import com.smartticket.exception.ApiFailure;
import com.smartticket.mapper.TicketMapper;
import com.smartticket.security.AccessPolicy;
import com.smartticket.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.smartticket.experience.ExperienceContract.*;

@Service
public class ExperienceService {
    private final TicketMapper tickets;
    private final AccessPolicy access;
    private final ExperienceRepository repo;
    private final ExperienceProperties config;
    private final JsonCodec json;
    public ExperienceService(TicketMapper tickets,AccessPolicy access,ExperienceRepository repo,
                             ExperienceProperties config,JsonCodec json) {
        this.tickets=tickets;this.access=access;this.repo=repo;this.config=config;this.json=json;
    }
    /** Serialize with reassignment/deletion, and authorize from current MySQL state, never Redis. */
    private Ticket authorized(long id) {
        if(!config.isEnabled()) throw new ApiFailure(404,40400,"一线经验采集未启用");
        var actor=ActorContext.require();
        if(!actor.admin()&&!"HANDLER".equals(actor.role())) throw ApiFailure.forbidden();
        Ticket ticket=tickets.selectOne(Wrappers.<Ticket>lambdaQuery().eq(Ticket::getId,id).last("FOR UPDATE"));
        if(ticket==null) throw new ApiFailure(404,40400,"工单不存在");
        access.read(ticket);
        return ticket;
    }
    private static ApiFailure invalid(String message) {return new ApiFailure(400,40000,message);}
    private static String requestId(String id) {
        try {if(UUID.fromString(id).toString().equals(id)) return id;}
        catch(IllegalArgumentException ignored){}
        throw invalid("requestId须为规范小写UUID");
    }
    private String hash(Object body) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.encode(body).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void version(Ticket ticket,long expected) {
        if(!Objects.equals(ticket.getVersion(),expected)) throw ApiFailure.conflict();
    }
    private Interaction target(long ticket,long id) {
        return repo.interaction(ticket,id).orElseThrow(()->new ApiFailure(404,40400,"本工单的处理记录不存在"));
    }
    private static void sameOrigin(Interaction original,Origin next) {
        if(original.origin()!=next) throw invalid("纠正记录必须保留原记录的数据来源类型");
    }
    @Transactional
    public Interaction append(long id,InteractionInput raw) {
        Ticket ticket=authorized(id);var actor=ActorContext.require();
        var body=new InteractionInput(requestId(raw.requestId()),raw.expectedVersion(),raw.kind(),raw.content().strip(),raw.origin(),
                raw.correctsInteractionId(),raw.evidenceRefs().stream().map(r->new Evidence(r.path().strip(),r.title().strip())).toList());
        if(body.content().length()<5||body.evidenceRefs().stream().anyMatch(r->r.path().isBlank()||r.title().isBlank())) throw invalid("处理内容或引用为空");
        String hash=hash(body);var previous=repo.interactionRequest(id,actor.id(),body.requestId());
        if(previous.isPresent()) {
            if(!previous.get().hash().equals(hash)) throw ApiFailure.conflict();
            return previous.get().record();
        }
        version(ticket,body.expectedVersion());
        if((body.kind()==Kind.CORRECTION)!=(body.correctsInteractionId()!=null)) throw invalid("CORRECTION必须指定被纠正记录，其他类型不得指定");
        if(body.correctsInteractionId()!=null) sameOrigin(target(id,body.correctsInteractionId()),body.origin());
        return repo.append(id,actor,body,hash);
    }
    @Transactional
    public Feedback feedback(long id,FeedbackInput raw) {
        Ticket ticket=authorized(id);var actor=ActorContext.require();
        var body=new FeedbackInput(requestId(raw.requestId()),raw.expectedVersion(),raw.interactionId(),raw.outcome(),raw.origin(),raw.comment().strip(),raw.correctionInteractionId());
        if(body.comment().length()<5) throw invalid("反馈说明过短");
        String hash=hash(body);var previous=repo.feedbackRequest(id,actor.id(),body.requestId());
        if(previous.isPresent()) {
            if(!previous.get().hash().equals(hash)) throw ApiFailure.conflict();
            return previous.get().record();
        }
        version(ticket,body.expectedVersion());
        Interaction original=target(id,body.interactionId());
        if(original.origin()==Origin.AI_SYNTHETIC_DEMO&&body.origin()!=FeedbackOrigin.SIMULATED_DEMO)
            throw invalid("合成记录不能标为实际使用反馈");
        if((body.outcome()==Outcome.CORRECTED)!=(body.correctionInteractionId()!=null)) throw invalid("CORRECTED必须关联纠正记录，其他结果不得关联");
        if(body.correctionInteractionId()!=null) {
            Interaction correction=target(id,body.correctionInteractionId());
            if(correction.kind()!=Kind.CORRECTION||!Objects.equals(correction.correctsInteractionId(),original.id()))
                throw invalid("纠正记录未指向被反馈的原记录");
        }
        return repo.append(id,actor,body,hash);
    }
    private static void paging(long after,int limit) {
        if(after<0||limit<1||limit>100) throw invalid("afterId须非负，limit须在1至100之间");
    }
    @Transactional
    public Page<Interaction> interactions(long id,long after,int limit) {
        authorized(id);paging(after,limit);var rows=repo.interactions(id,after,limit+1);
        return new Page<>(List.copyOf(rows.subList(0,Math.min(limit,rows.size()))),rows.size()>limit?rows.get(limit-1).id():null);
    }
    @Transactional
    public Page<Feedback> feedback(long id,long after,int limit) {
        authorized(id);paging(after,limit);var rows=repo.feedback(id,after,limit+1);
        return new Page<>(List.copyOf(rows.subList(0,Math.min(limit,rows.size()))),rows.size()>limit?rows.get(limit-1).id():null);
    }
}
