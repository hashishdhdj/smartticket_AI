package com.smartticket.experience.graph;

import com.smartticket.experience.*;
import com.smartticket.knowledge.KnowledgeSource;
import java.util.Optional;
import org.springframework.stereotype.Component;
import static com.smartticket.experience.graph.ExperienceGraph.*;

/** Immutable MySQL version -> validated deterministic PG input, before embedding I/O. */
@Component
public class ExperienceGraphSource {
    private final ExperienceProperties config;
    private final ExperienceCardRepository cards;
    private final ExperienceGraphCompiler compiler;
    public ExperienceGraphSource(ExperienceProperties config,ExperienceCardRepository cards,ExperienceGraphCompiler compiler){
        this.config=config;this.cards=cards;this.compiler=compiler;
    }
    public Optional<Plan> plan(KnowledgeSource source,String question,String answer,String textHash) {
        if(!config.isGraphIndexEnabled())return Optional.empty();
        var version=cards.version(source.candidateId(),source.revision());
        if(version.isEmpty()) {
            if(cards.hasVersions(source.candidateId()))throw new IllegalStateException("Missing graph source version");
            return Optional.empty();
        }
        var v=version.get();var card=cards.card(v.cardId());var submission=cards.submission(v.submissionId());
        if(!"APPROVED".equals(v.state())||!submission.cardId().equals(card.id())||!card.hash().equals(v.cardHash())
                ||!textHash.equals(v.publicationHash())||!card.question().equals(question)||!card.answer().equals(answer)
                ||!cards.evidenceHealthy(submission.id()))throw new IllegalStateException("Graph source is not a healthy approved version");
        var plan=compiler.compile(card,submission.id());
        if(!plan.textHash().equals(textHash))throw new IllegalStateException("Graph source text hash changed");
        return Optional.of(plan);
    }
}
