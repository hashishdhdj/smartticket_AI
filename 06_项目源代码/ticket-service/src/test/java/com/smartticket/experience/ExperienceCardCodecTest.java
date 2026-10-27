package com.smartticket.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.common.JsonCodec;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.smartticket.experience.ExperienceCardContract.*;
import static com.smartticket.experience.ExperienceContract.*;

class ExperienceCardCodecTest {
    private static jakarta.validation.ValidatorFactory factory;
    private ExperienceCardCodec codec;
    @BeforeAll static void start(){factory=Validation.buildDefaultValidatorFactory();}
    @AfterAll static void stop(){factory.close();}
    @BeforeEach void setup(){codec=new ExperienceCardCodec(new JsonCodec(new ObjectMapper()),factory.getValidator());}
    private Card card(List<String> conditions,List<String> steps){return new Card("演示账号密码重置",List.of("密码遗忘"),conditions,steps,
            List.of("旧号停用时不可短信重置"),List.of(),List.of(),List.of(new Evidence("faq/account.md","演示重置规则")),
            FactScope.SIMULATED_PLATFORM_RULE_NOT_IMPLEMENTED_FEATURE,SupportStatus.REFERENCED_DEMO);}
    @Test void harmlessSetOrderingAndOuterWhitespaceNormalizeToSameHash(){
        var a=codec.normalize(card(List.of(" 原手机号可用 ","仅限演示"),List.of(" 验证后设置新密码 ")));
        var b=codec.normalize(card(List.of("仅限演示","原手机号可用","仅限演示"),List.of("验证后设置新密码")));
        assertEquals(a,b);assertEquals(codec.hash(a),codec.hash(b));
    }
    @Test void negationChangesHash(){assertNotEquals(codec.hash(codec.normalize(card(List.of("手机号可用"),List.of("核实原手机号状态")))),
            codec.hash(codec.normalize(card(List.of("手机号不可用"),List.of("核实原手机号状态")))));}
    @Test void numericLimitsAndVersionsAreNotDiscarded(){assertNotEquals(codec.hash(codec.normalize(card(List.of("版本1，上限10MB"),List.of("验证大小后上传")))),
            codec.hash(codec.normalize(card(List.of("版本2，上限15MB"),List.of("验证大小后上传")))));}
    @Test void stepOrderIsSemanticallySignificant(){assertNotEquals(codec.hash(codec.normalize(card(List.of("身份已验证"),List.of("先读当前字段","再提交全量更新")))),
            codec.hash(codec.normalize(card(List.of("身份已验证"),List.of("再提交全量更新","先读当前字段")))));}
    @Test void innerWhitespaceAndPunctuationArePreserved(){
        var c=codec.normalize(card(List.of("错误码 A B 不等于 AB"),List.of("核对 8~20 位规则；不可省略前提。")));
        assertEquals("错误码 A B 不等于 AB",c.conditions().get(0));assertTrue(codec.answer(c).contains("8~20"));
    }
    @Test void emptyAndWhitespaceConditionsFailRatherThanBroadenScope(){
        assertThrows(RuntimeException.class,()->codec.normalize(card(List.of(),List.of("检查账号状态"))));
        assertThrows(RuntimeException.class,()->codec.normalize(card(List.of("   "),List.of("检查账号状态"))));
    }
    @Test void rendererKeepsFailuresAndHypothesesExplicitlySeparate(){
        var c=card(List.of("仅用于演示"),List.of("核实完整请求字段"));
        c=new Card(c.problemType(),c.symptoms(),c.conditions(),c.steps(),c.contraindications(),List.of("错误做法：只传标题一定保留描述"),
                List.of("推测客户端省略字段，尚未证实"),c.evidenceRefs(),c.factScope(),c.supportStatus());
        String rendered=codec.answer(codec.normalize(c));assertTrue(rendered.contains("失败反例（不得作为成功步骤）"));
        assertTrue(rendered.contains("待验证假设（非已确认原因）"));assertTrue(rendered.contains("不代表SmartTicket已经实现"));
    }
    @Test void unknownSupportAndFactScopeCannotBeClaimedAsVerified(){
        var c=card(List.of("手机号可用"),List.of("核对演示说明"));
        assertThrows(RuntimeException.class,()->codec.normalize(new Card(c.problemType(),c.symptoms(),c.conditions(),c.steps(),
                c.contraindications(),c.counterexamples(),c.hypotheses(),c.evidenceRefs(),null,c.supportStatus())));
    }
    @Test void overlongRenderedEvidenceIsRejectedWithoutTruncation(){
        var c=card(List.of("仅限演示"),Collections.nCopies(8,"步骤".repeat(85)));
        assertThrows(RuntimeException.class,()->codec.normalize(c));
    }
    @Test void lexicalMatchIsOnlyAHintAndStillSeesChangedCondition(){
        assertTrue(ExperienceCardService.similarity("演示账号忘记密码","演示账号忘记密码且旧号停用")>0.35);
        assertNotEquals(codec.hash(codec.normalize(card(List.of("原手机号可用"),List.of("核对流程")))),
                codec.hash(codec.normalize(card(List.of("原手机号不可用"),List.of("核对流程")))));
    }
    @Test void absentPaymentIntegrationKeepsScopeAndCannotClaimReferencedSuccessfulRefund(){
        var c=card(List.of("缺少实际银行处理记录"),List.of("说明资料不足并请有权限人员核实"));
        var unknown=new Card(c.problemType(),c.symptoms(),c.conditions(),c.steps(),c.contraindications(),c.counterexamples(),c.hypotheses(),
                c.evidenceRefs(),FactScope.SIMULATED_CUSTOMER_QUESTION_NO_PAYMENT_INTEGRATION,SupportStatus.UNVERIFIED);
        assertTrue(codec.answer(codec.normalize(unknown)).contains("不能确认实际到账"));
        assertThrows(RuntimeException.class,()->codec.normalize(new Card(c.problemType(),c.symptoms(),c.conditions(),c.steps(),c.contraindications(),
                c.counterexamples(),c.hypotheses(),c.evidenceRefs(),unknown.factScope(),SupportStatus.REFERENCED_DEMO)));
    }
}
