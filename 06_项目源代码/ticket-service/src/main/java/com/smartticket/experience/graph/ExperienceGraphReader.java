package com.smartticket.experience.graph;

import com.smartticket.experience.ExperienceProperties;
import com.smartticket.knowledge.*;
import com.smartticket.rag.*;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.smartticket.experience.graph.ExperienceGraph.*;

/** Read-only, source-bound view. No admin inspection metadata or raw staff/ticket records. */
@Component
public class ExperienceGraphReader {
    private final ExperienceProperties config;
    private final KnowledgeWorkflowProperties workflow;
    private final KnowledgeWorkflowRepository knowledge;
    private final ExperienceGraphSource source;
    private final ExperienceGraphReceiptRepository receipts;
    private final KnowledgeVectorRepository vectors;
    private final EmbeddingService embeddings;

    public ExperienceGraphReader(ExperienceProperties config, KnowledgeWorkflowProperties workflow,
            KnowledgeWorkflowRepository knowledge, ExperienceGraphSource source,
            ExperienceGraphReceiptRepository receipts, KnowledgeVectorRepository vectors, EmbeddingService embeddings) {
        this.config=config; this.workflow=workflow; this.knowledge=knowledge; this.source=source;
        this.receipts=receipts; this.vectors=vectors; this.embeddings=embeddings;
    }

    public boolean enabled() {
        return config.isEnabled() && config.isCardsEnabled() && config.isGraphIndexEnabled()
                && config.isGraphSearchEnabled() && workflow.isEnabled();
    }

    public Optional<Snapshot> read(KnowledgeSource key) {
        if (!enabled() || !knowledge.visibleSources(Set.of(key.candidateId())).contains(key.file())) return Optional.empty();
        var c=knowledge.candidate(key.candidateId(),false);
        if (c.revision()!=key.revision() || !"APPROVED".equals(c.state()) || !Objects.equals(c.indexedRevision(),key.revision()))
            return Optional.empty();
        String hash=KnowledgeSource.hash(KnowledgeSource.content(c.question(),c.answer()));
        var plan=source.plan(key,c.question(),c.answer(),hash).orElse(null);
        if (plan==null || !receipts.matches(key,plan) || !vectors.publicationMatches(key,hash,embeddings.modelName())
                || !vectors.graphMatches(key,plan)) return Optional.empty();
        var graph=vectors.graph(key);
        // Compare the snapshot actually returned, rather than a prior read before a revision change.
        if (graph==null || !"ACTIVE".equals(graph.publication().state())
                || !key.file().equals(graph.publication().sourceFile()) || !plan.graphHash().equals(graph.publication().graphHash())
                || !key.candidateId().equals(graph.publication().candidateId()) || key.revision()!=graph.publication().revision()
                || !plan.cardId().equals(graph.publication().cardId()) || !plan.submissionId().equals(graph.publication().submissionId())
                || !COMPILER.equals(graph.publication().compilerVersion()) || !plan.factScope().equals(graph.publication().factScope())
                || plan.evidence().size()!=graph.publication().evidenceCount()
                || !plan.cardHash().equals(graph.publication().cardHash()) || !plan.textHash().equals(graph.publication().textHash())
                || !plan.nodes().equals(graph.nodes()) || !plan.edges().equals(graph.edges()) || !plan.evidence().equals(graph.evidence())
                || !knowledge.visibleSources(Set.of(key.candidateId())).contains(key.file())) return Optional.empty();
        return Optional.of(graph);
    }
}
