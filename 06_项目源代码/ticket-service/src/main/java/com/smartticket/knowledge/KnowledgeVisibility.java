package com.smartticket.knowledge;

import com.smartticket.rag.RetrievalHit;
import java.util.*;
import org.springframework.stereotype.Component;

/** MySQL is the authority even when PG cleanup/acknowledgement is delayed. */
@Component
public class KnowledgeVisibility {
    private final KnowledgeWorkflowRepository repo;
    private final KnowledgeWorkflowProperties config;
    public KnowledgeVisibility(KnowledgeWorkflowRepository repo,KnowledgeWorkflowProperties config) {
        this.repo=repo;this.config=config;
    }

    public List<RetrievalHit> filter(List<RetrievalHit> hits) {
        Set<String> ids=new HashSet<>();
        for(var hit:hits) if(hit.chunk().sourceFile().startsWith("resolved-ticket/")) {
            try { ids.add(KnowledgeSource.parse(hit.chunk().sourceFile()).candidateId()); }
            catch(IllegalArgumentException invalid) { /* Unregistered source keys never pass. */ }
        }
        Set<String> visible=config.isEnabled()?repo.visibleSources(ids):Set.of();
        return hits.stream().filter(hit->!hit.chunk().sourceFile().startsWith("resolved-ticket/")||visible.contains(hit.chunk().sourceFile())).toList();
    }
}
