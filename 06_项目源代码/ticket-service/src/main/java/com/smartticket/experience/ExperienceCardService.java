package com.smartticket.experience;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartticket.common.JsonCodec;
import com.smartticket.entity.Ticket;
import com.smartticket.exception.ApiFailure;
import com.smartticket.knowledge.KnowledgeWorkflowRepository;
import com.smartticket.knowledge.KnowledgeWorkflowProperties;
import com.smartticket.knowledge.KnowledgeSource;
import com.smartticket.mapper.TicketMapper;
import com.smartticket.security.*;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.smartticket.experience.ExperienceCardContract.*;
import static com.smartticket.experience.ExperienceContract.*;
import static com.smartticket.experience.ExperienceCardCodec.invalid;

@Service
public class ExperienceCardService {
    private final ExperienceProperties config;
    private final ExperienceCardRepository repo;
    private final ExperienceRepository notes;
    private final ExperienceCardCodec codec;
    private final TicketMapper tickets;
    private final AccessPolicy access;
    private final KnowledgeWorkflowRepository knowledge;
    private final KnowledgeWorkflowProperties workflow;
    private final JsonCodec json;
    private final Validator validator;
    private final JdbcTemplate db;
    public ExperienceCardService(ExperienceProperties config,ExperienceCardRepository repo,ExperienceRepository notes,
            ExperienceCardCodec codec,TicketMapper tickets,AccessPolicy access,KnowledgeWorkflowRepository knowledge,
            KnowledgeWorkflowProperties workflow,JsonCodec json,Validator validator,JdbcTemplate db) {
        this.config=config;this.repo=repo;this.notes=notes;this.codec=codec;this.tickets=tickets;this.access=access;
        this.knowledge=knowledge;this.workflow=workflow;this.json=json;this.validator=validator;this.db=db;
    }
    private void enabled(){if(!config.isEnabled()||!config.isCardsEnabled())throw new ApiFailure(404,40400,"经验卡未启用");}
    private Ticket ticket(long id) {
        enabled();var a=ActorContext.require();if(!a.admin()&&!"HANDLER".equals(a.role()))throw ApiFailure.forbidden();
        var t=tickets.selectOne(Wrappers.<Ticket>lambdaQuery().eq(Ticket::getId,id).last("FOR UPDATE"));
        if(t==null)throw new ApiFailure(404,40400,"工单不存在");access.read(t);return t;
    }
    private static String uuid(String s){
        try{if(UUID.fromString(s).toString().equals(s))return s;}catch(Exception ignored){}
        throw invalid("需要规范小写UUID");
    }
    private List<Interaction> load(long ticket,List<Long> ids){
        return ids.stream().map(id->notes.interaction(ticket,id).orElseThrow(()->new ApiFailure(404,40400,"本工单的处理记录不存在"))).toList();
    }
    private Prepared prepared(Submission submission) {
        var card=repo.card(submission.cardId());var related=new ArrayList<Related>();
        // Draft hints are admin-only; never disclose another handler's unreviewed record.
        if(ActorContext.require().admin())for(var other:repo.recent()) {
            if(other.id().equals(card.id()))continue;double score=similarity(card.content().problemType(),other.content().problemType());
            if(score>=0.35)related.add(new Related(other.id(),other.content().problemType(),score,"LEXICAL_HINT_ONLY_REVIEW_CONDITIONS"));
        }
        related.sort(Comparator.comparingDouble(Related::lexicalSimilarity).reversed().thenComparing(Related::cardId));
        return new Prepared(submission,card,submission.duplicateCard(),repo.reusable(card.id()),List.copyOf(related.subList(0,Math.min(5,related.size()))),ExperienceCardCodec.SCHEMA);
    }
    private static Set<String> grams(String s){
        var values=new HashSet<String>();int[] chars=s.codePoints().toArray();
        for(int i=0;i+1<chars.length;i++)values.add(new String(chars,i,2));return values;
    }
    static double similarity(String a,String b){
        var x=grams(a);var y=grams(b);var union=new HashSet<>(x);union.addAll(y);x.retainAll(y);
        return union.isEmpty()?0:(double)x.size()/union.size();
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Prepared prepare(long ticketId,Prepare raw) {
        Ticket t=ticket(ticketId);var actor=ActorContext.require();
        if(raw==null||!validator.validate(raw).isEmpty())throw invalid("经验提交字段不合法");
        Card content=codec.normalize(raw.card());
        var positive=raw.positiveInteractionIds().stream().distinct().sorted().toList();
        var negative=raw.counterexampleInteractionIds().stream().distinct().sorted().toList();
        var body=new Prepare(uuid(raw.requestId()),raw.expectedVersion(),positive,negative,content);
        String hash=KnowledgeSource.hash(json.encode(body));var previous=repo.request(ticketId,actor.id(),body.requestId());
        if(previous.isPresent()) {
            if(!previous.get().requestHash().equals(hash))throw ApiFailure.conflict();return prepared(previous.get());
        }
        if(!Objects.equals(t.getVersion(),body.expectedVersion()))throw ApiFailure.conflict();
        if(positive.stream().anyMatch(negative::contains))throw invalid("同一记录不能同时是成功候选与失败反例");
        var positives=load(ticketId,positive);var negatives=load(ticketId,negative);
        var all=new ArrayList<>(positives);all.addAll(negatives);
        if(all.stream().anyMatch(n->n.origin()!=Origin.AI_SYNTHETIC_DEMO))throw invalid("首版仅允许合成共享演示经验，不自动共享真实员工记录");
        for(var note:positives) {
            if(repo.failed(ticketId,note.id()))throw invalid("已有失败或被纠正记录不能作为正向步骤依据");
            if(note.kind()==Kind.CORRECTION&&!negative.contains(note.correctsInteractionId()))throw invalid("纠正经验必须同时保留原错误记录作为反例");
        }
        for(var note:negatives)if(!repo.failed(ticketId,note.id()))throw invalid("失败反例必须关联已有失败或纠正反馈");
        if(!negative.isEmpty()&&content.counterexamples().isEmpty())throw invalid("存在失败原文时经验卡必须保存失败反例说明");
        var declared=new HashSet<Evidence>();for(var note:all)declared.addAll(note.evidenceRefs());
        if(!declared.containsAll(content.evidenceRefs()))throw invalid("经验卡引用必须来自选定员工记录，仍待审核核实");
        var stored=repo.ensureCard(content,codec.hash(content),codec.answer(content));
        var feedback=all.stream().map(n->Map.of("interactionId",n.id(),"records",repo.feedback(ticketId,n.id()))).toList();
        var evidence=Map.of("positiveInteractionIds",positive,"counterexampleInteractionIds",negative,
                "interactions",all,"feedbackAtPreparation",feedback,"extractionMethod",ExperienceCardCodec.SCHEMA,
                "businessOutcomeIndependentlyVerified",false);
        var submission=repo.insert(ticketId,t.getVersion(),actor.id(),actor.role(),body,hash,stored.card().id(),stored.duplicate(),evidence);
        return prepared(submission);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<Map<String,Object>> list(long id,int limit){ticket(id);if(limit<1||limit>100)throw invalid("limit须为1至100");return repo.ticketSubmissions(id,limit);}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> detail(long id,String submissionId) {
        ticket(id);var s=repo.submission(uuid(submissionId));if(s.ticketId()!=id)throw new ApiFailure(404,40400,"本工单的经验提交不存在");
        return Map.of("prepared",prepared(s),"evidence",repo.snapshot(s.id()));
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Version bind(String candidateId,Binding body) {
        enabled();if(!ActorContext.require().admin())throw ApiFailure.forbidden();
        if(!workflow.isEnabled())throw new ApiFailure(404,40400,"知识工作流未启用");
        var submission=repo.submission(uuid(body.submissionId()));ticket(submission.ticketId());
        var candidate=knowledge.candidate(uuid(candidateId),true);
        if(candidate.ticketId()!=submission.ticketId())throw invalid("知识候选与经验提交必须来自同一工单");
        if(body.expectedRevision()==null||candidate.revision()!=body.expectedRevision())throw ApiFailure.conflict();
        var card=repo.card(submission.cardId());var previous=repo.version(candidate.id(),candidate.revision());
        if(previous.isPresent()&&previous.get().submissionId().equals(submission.id()))return previous.get();
        if("APPROVED".equals(candidate.state()))throw invalid("已发布经验须先撤回再修订，不能静默覆盖");
        long next=candidate.revision();
        if(previous.isPresent()||!"DRAFT".equals(candidate.state()))next++;
        int changed=db.update("UPDATE knowledge_candidate SET revision=?,state='DRAFT',question=?,answer=?,indexed_revision=NULL,content_hash=NULL"
                +" WHERE candidate_id=? AND revision=?",next,card.question(),card.answer(),candidate.id(),candidate.revision());
        if(changed!=1)throw ApiFailure.conflict();
        return repo.bind(candidate.id(),next,"DRAFT",submission,card,ActorContext.require().id());
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> binding(String candidateId) {
        enabled();if(!ActorContext.require().admin())throw ApiFailure.forbidden();
        var c=knowledge.candidate(uuid(candidateId),false);ticket(c.ticketId());
        var v=repo.version(c.id(),c.revision());
        return v.isEmpty()?Map.of():Map.of("version",v.get(),"card",repo.card(v.get().cardId()),"evidence",repo.snapshot(v.get().submissionId()),
                "evidenceHealthy",repo.evidenceHealthy(v.get().submissionId()));
    }
}
