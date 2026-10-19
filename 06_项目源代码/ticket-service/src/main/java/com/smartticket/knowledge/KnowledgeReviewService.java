package com.smartticket.knowledge;

import com.smartticket.exception.ApiFailure;
import com.smartticket.mapper.TicketMapper;
import com.smartticket.security.AccessPolicy;
import com.smartticket.security.ActorContext;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeReviewService {
    private static final Pattern OBVIOUS_SENSITIVE = Pattern.compile(
            "(?i)([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)|sk-[A-Z0-9_-]{16,}|(?:password|api[-_]?key|密码)\\s*[:=：]\\s*\\S{4,})");
    private final KnowledgeWorkflowRepository repo;
    private final KnowledgeWorkflowProperties config;
    private final AccessPolicy access;
    private final TicketMapper tickets;

    public KnowledgeReviewService(KnowledgeWorkflowRepository repo,KnowledgeWorkflowProperties config,
                                  AccessPolicy access,TicketMapper tickets) {
        this.repo=repo;this.config=config;this.access=access;this.tickets=tickets;
    }

    private void admin() {
        ActorContext.require(); // Never permit review through legacy security-disabled mode.
        access.admin();
        if(!config.isEnabled()) throw new ApiFailure(404,40400,"知识工作流未启用");
    }

    private static String id(String value) {
        try { if(!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); return value; }
        catch(Exception invalid){throw new ApiFailure(400,40000,"需要UUID格式的标识");}
    }

    private KnowledgeWorkflowRepository.Candidate expected(String value,Long revision) {
        var candidate=repo.candidate(id(value),true);
        if(revision==null||revision<0) throw new ApiFailure(400,40000,"需要非负expectedRevision");
        if(candidate.revision()!=revision) throw ApiFailure.conflict();
        var ticket=tickets.selectById(candidate.ticketId());
        if(ticket==null) throw new ApiFailure(404,10003,"工单不存在或已删除");
        return candidate;
    }

    public List<KnowledgeWorkflowRepository.Candidate> list(int limit) {
        admin();if(limit<1||limit>50) throw new ApiFailure(400,40000,"limit须为1到50");
        return repo.candidates(limit);
    }

    public Map<String,Object> detail(String candidate) {
        admin();String key=id(candidate);
        return Map.of("candidate",repo.candidate(key,false),"jobs",repo.jobs(key));
    }

    public List<Map<String,Object>> ticketState(long ticketId) {
        ActorContext.require();
        var ticket=tickets.selectById(ticketId);
        if(ticket==null) throw new ApiFailure(404,10003,"工单不存在");
        access.read(ticket);
        if(!config.isEnabled()) throw new ApiFailure(404,40400,"知识工作流未启用");
        return repo.ticketResolutions(ticketId);
    }

    @Transactional(rollbackFor=Exception.class)
    public KnowledgeWorkflowRepository.Candidate approve(String candidate,Long revision,String question,String answer,
                                                         String scope,Boolean redactionConfirmed) {
        admin();var old=expected(candidate,revision);
        if(!Boolean.TRUE.equals(redactionConfirmed)||!"SHARED_DEMO".equals(scope))
            throw new ApiFailure(400,40000,"仅允许经人工脱敏确认的共享演示知识");
        question=bounded(question,5,500);answer=bounded(answer,10,1500);
        if(OBVIOUS_SENSITIVE.matcher(question+"\n"+answer).find()) throw new ApiFailure(400,40000,"请先移除明显联系方式或凭据，再提交人工审核");
        return repo.review(old,"APPROVED",question,answer,ActorContext.require().id());
    }

    private static String bounded(String text,int min,int max) {
        if(text==null||text.strip().length()<min||text.strip().length()>max) throw new ApiFailure(400,40000,"审核问答长度不符合要求");
        return text.strip();
    }

    @Transactional(rollbackFor=Exception.class)
    public KnowledgeWorkflowRepository.Candidate reject(String candidate,Long revision) {
        admin();var old=expected(candidate,revision);
        if(!"DRAFT".equals(old.state())) throw ApiFailure.conflict();
        return repo.review(old,"REJECTED",old.question(),old.answer(),ActorContext.require().id());
    }

    @Transactional(rollbackFor=Exception.class)
    public KnowledgeWorkflowRepository.Candidate retract(String candidate,Long revision) {
        admin();var old=expected(candidate,revision);
        if(!"APPROVED".equals(old.state())) throw ApiFailure.conflict();
        return repo.review(old,"RETRACTED",old.question(),old.answer(),ActorContext.require().id());
    }

    @Transactional(rollbackFor=Exception.class)
    public void retry(String jobId,Long revision) {
        admin();String key=id(jobId);
        var job=repo.jobRow(key,false);
        long jobRevision=((Number)job.get("revision")).longValue();
        if(revision==null||revision!=jobRevision) throw ApiFailure.conflict();
        String resolution=(String)job.get("resolution_id");
        if(!"DRAFT".equals(job.get("kind"))) {
            var candidate=expected(resolution,revision);
            String required="INDEX".equals(job.get("kind"))?"APPROVED":"RETRACTED";
            if(!required.equals(candidate.state())) throw ApiFailure.conflict();
            repo.audit(resolution,revision,ActorContext.require().id(),"RETRY",null);
        }
        repo.replay(key,ActorContext.require().id());
    }
}
