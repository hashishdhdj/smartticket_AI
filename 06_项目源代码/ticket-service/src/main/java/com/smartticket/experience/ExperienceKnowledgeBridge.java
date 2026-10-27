package com.smartticket.experience;

import com.smartticket.exception.ApiFailure;
import com.smartticket.knowledge.KnowledgeWorkflowRepository.Candidate;
import org.springframework.stereotype.Component;
import static com.smartticket.experience.ExperienceCardContract.*;
import static com.smartticket.experience.ExperienceCardCodec.invalid;

/** Runs inside the existing review transaction, including when new-card creation is disabled. */
@Component
public class ExperienceKnowledgeBridge {
    private final ExperienceCardRepository repo;
    private final ExperienceCardCodec codec;
    public ExperienceKnowledgeBridge(ExperienceCardRepository repo,ExperienceCardCodec codec){this.repo=repo;this.codec=codec;}
    public boolean visible(String candidate,long revision) {
        var v=repo.version(candidate,revision);
        if(v.isEmpty())return !repo.hasVersions(candidate);
        return "APPROVED".equals(v.get().state())&&repo.evidenceHealthy(v.get().submissionId());
    }
    public void review(Candidate old,String state,String question,String answer,long actor) {
        var version=repo.version(old.id(),old.revision());
        if(version.isEmpty()) {
            if(repo.hasVersions(old.id()))throw new IllegalStateException("Missing experience revision binding");
            return;
        }
        var v=version.get();var card=repo.card(v.cardId());var submission=repo.submission(v.submissionId());
        if(submission.ticketId()!=old.ticketId()||!v.state().equals(old.state())||!card.hash().equals(v.cardHash())
                ||!codec.hash(card.content()).equals(card.hash())||!codec.answer(card.content()).equals(card.answer())
                ||!com.smartticket.knowledge.KnowledgeSource.hash(com.smartticket.knowledge.KnowledgeSource.content(card.question(),card.answer())).equals(v.publicationHash()))
            throw new IllegalStateException("Experience content or provenance changed");
        if(!card.question().equals(question)||!card.answer().equals(answer))throw invalid("审核文本必须与结构化经验一致；请通过新经验卡修订");
        if("APPROVED".equals(state)) {
            if(card.content().supportStatus()==SupportStatus.UNVERIFIED)throw invalid("资料待核实的经验不能发布为已审核知识");
            for(long id:repo.positives(submission.id()))if(repo.failed(submission.ticketId(),id))
                throw new ApiFailure(409,10011,"正向依据新增失败或纠正反馈，请重新整理经验");
        }
        repo.bind(old.id(),old.revision()+1,state,submission,card,actor);
    }
}
