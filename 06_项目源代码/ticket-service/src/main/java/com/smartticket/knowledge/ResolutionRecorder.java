package com.smartticket.knowledge;

import com.smartticket.entity.Ticket;
import com.smartticket.exception.ApiFailure;
import com.smartticket.security.ActorContext;
import org.springframework.stereotype.Service;

@Service
public class ResolutionRecorder {
    private final KnowledgeWorkflowRepository repo;
    private final KnowledgeWorkflowProperties config;

    public ResolutionRecorder(KnowledgeWorkflowRepository repo, KnowledgeWorkflowProperties config) {
        this.repo=repo;this.config=config;
    }

    public void record(Ticket ticket,String solution) {
        if(solution==null) return; // Legacy resolve requests remain compatible.
        if(!config.isEnabled()) throw new ApiFailure(503,10006,"知识工作流未启用，请移除solution或完成配置");
        String normalized=solution.strip();
        if(normalized.length()<10||normalized.length()>2000) throw new ApiFailure(400,40000,"解决方案须为10到2000个字符");
        repo.recordResolution(ticket,ActorContext.require().id(),normalized);
    }
}
