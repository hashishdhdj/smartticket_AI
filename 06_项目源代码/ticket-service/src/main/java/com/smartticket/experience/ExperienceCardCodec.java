package com.smartticket.experience;

import com.smartticket.common.JsonCodec;
import com.smartticket.exception.ApiFailure;
import com.smartticket.knowledge.KnowledgeSource;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.smartticket.experience.ExperienceCardContract.*;
import static com.smartticket.experience.ExperienceContract.*;

/** Conservative normalization: never erase negation, numbers or internal whitespace. */
@Component
public class ExperienceCardCodec {
    public static final String SCHEMA="provided-fields-v1";
    private final JsonCodec json;
    private final Validator validator;
    public ExperienceCardCodec(JsonCodec json,Validator validator){this.json=json;this.validator=validator;}
    private static List<String> unordered(List<String> values){return values.stream().map(String::strip).distinct().sorted().toList();}
    public Card normalize(Card raw) {
        if(raw==null||!validator.validate(raw).isEmpty()) throw invalid("经验卡字段不符合schema");
        var refs=raw.evidenceRefs().stream().map(e->new Evidence(e.path().strip(),e.title().strip())).distinct()
                .sorted(Comparator.comparing(Evidence::path).thenComparing(Evidence::title)).toList();
        var card=new Card(raw.problemType().strip(),unordered(raw.symptoms()),unordered(raw.conditions()),
                raw.steps().stream().map(String::strip).toList(),unordered(raw.contraindications()),
                unordered(raw.counterexamples()),unordered(raw.hypotheses()),refs,raw.factScope(),raw.supportStatus());
        if(!validator.validate(card).isEmpty()) throw invalid("经验卡去除首尾空白后字段不合法");
        if(card.supportStatus()==SupportStatus.REFERENCED_DEMO&&refs.isEmpty()) throw invalid("有依据的演示指引必须提供引用");
        if(card.factScope()==FactScope.SIMULATED_CUSTOMER_QUESTION_NO_PAYMENT_INTEGRATION&&card.supportStatus()!=SupportStatus.UNVERIFIED)
            throw invalid("未接入实际支付记录的合成退款问题必须保持待核实");
        if(answer(card).length()>1500) throw invalid("完整经验卡超过1500字，请精简，禁止截断适用条件");
        return card;
    }
    public String hash(Card card){return KnowledgeSource.hash(SCHEMA+"\n"+json.encode(card));}
    public String answer(Card c) {
        String scope=switch(c.factScope()) {
            case PROJECT_RULE->"本项目规则的演示经验，需核对具体版本。";
            case SIMULATED_PLATFORM_RULE_NOT_IMPLEMENTED_FEATURE->"模拟平台规则，不代表SmartTicket已经实现相关业务。";
            case SIMULATED_CUSTOMER_QUESTION_NO_PAYMENT_INTEGRATION->"合成客户问题；本项目未集成真实支付或退款，不能确认实际到账。";
        };
        return "适用范围："+scope+"\n支持状态："+c.supportStatus()+"；实际业务效果未独立核实。"
                +section("症状",c.symptoms())+section("适用条件",c.conditions())+section("处理步骤",c.steps())
                +section("禁止条件",c.contraindications())+section("失败反例（不得作为成功步骤）",c.counterexamples())
                +section("待验证假设（非已确认原因）",c.hypotheses())
                +section("待审核依据",c.evidenceRefs().stream().map(e->e.path()+"#"+e.title()).toList());
    }
    private static String section(String name,List<String> lines) {
        if(lines.isEmpty()) return "";
        StringBuilder result=new StringBuilder("\n"+name+"：");
        for(int i=0;i<lines.size();i++) result.append("\n").append(i+1).append(". ").append(lines.get(i));
        return result.toString();
    }
    public static ApiFailure invalid(String message){return new ApiFailure(400,40000,message);}
}
