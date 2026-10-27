package com.smartticket.experience.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.common.JsonCodec;
import com.smartticket.experience.*;
import com.smartticket.experience.ExperienceCardContract.*;
import com.smartticket.experience.ExperienceContract.Evidence;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.smartticket.experience.graph.ExperienceGraph.*;

class ExperienceGraphCompilerTest {
    private static jakarta.validation.ValidatorFactory factory;
    private ExperienceCardCodec codec;
    private ExperienceGraphCompiler compiler;
    @BeforeAll static void start(){factory=Validation.buildDefaultValidatorFactory();}
    @AfterAll static void stop(){factory.close();}
    @BeforeEach void setup(){var json=new JsonCodec(new ObjectMapper());codec=new ExperienceCardCodec(json,factory.getValidator());compiler=new ExperienceGraphCompiler(json,codec);}
    private Card card(String condition,List<String> steps){return new Card("合成账号异常排查",List.of("登录失败"),List.of(condition),steps,
            List.of("未核实身份不可重置"),List.of("错误做法：跳过身份核实"),List.of("可能是配置异常，尚未验证"),
            List.of(new Evidence("faq/demo.md","合成演示规则")),FactScope.SIMULATED_PLATFORM_RULE_NOT_IMPLEMENTED_FEATURE,SupportStatus.REFERENCED_DEMO);}
    private StoredCard stored(Card raw){var c=codec.normalize(raw);return new StoredCard("fbb9ec1a-bd46-402a-8761-f2b8e846c001",codec.hash(c),c,c.problemType(),codec.answer(c));}
    private Plan compile(Card c){return compiler.compile(stored(c),"311941b4-64fb-4118-b9dc-f11b2c650001");}
    @Test void deterministicCompilationHasStableTypedIdentities(){
        var c=card("手机号可用",List.of("核实身份","设置新密码"));var p=compile(c);assertEquals(p,compile(c));
        assertTrue(p.nodes().stream().allMatch(n->n.id().matches("[0-9a-f]{64}")));
        assertTrue(p.edges().stream().allMatch(e->e.id().matches("[0-9a-f]{64}")));
    }
    @Test void sameCardDifferentContributorsShareConceptsButNotPublicationIdentity(){
        var c=stored(card("手机号可用",List.of("核实身份","设置新密码")));
        var a=compiler.compile(c,UUID.randomUUID().toString());var b=compiler.compile(c,UUID.randomUUID().toString());
        assertEquals(a.nodes(),b.nodes());assertEquals(a.edges(),b.edges());assertEquals(a.graphHash(),b.graphHash());assertNotEquals(a.submissionId(),b.submissionId());
    }
    @Test void negationAndVersionAreDistinctConditionNodes(){
        var a=compile(card("手机号可用；版本1",List.of("核实身份")));var b=compile(card("手机号不可用；版本2",List.of("核实身份")));
        assertNotEquals(a.graphHash(),b.graphHash());
        assertNotEquals(a.nodes().stream().filter(n->n.key().contains("版本1")).findFirst().orElseThrow().id(),b.nodes().stream().filter(n->n.key().contains("版本2")).findFirst().orElseThrow().id());
    }
    @Test void noFailureIsTurnedIntoAnActionStep(){
        var p=compile(card("手机号可用",List.of("核实身份")));
        var failure=p.nodes().stream().filter(n->n.kind()==Kind.COUNTEREXAMPLE).findFirst().orElseThrow();
        assertTrue(p.edges().stream().anyMatch(e->e.to().equals(failure.id())&&e.relation()==Relation.HAS_COUNTEREXAMPLE));
        assertFalse(p.edges().stream().anyMatch(e->e.to().equals(failure.id())&&e.relation()==Relation.ADDRESSED_BY));
    }
    @Test void hypothesesAreNotConfirmedCauses(){
        var p=compile(card("手机号可用",List.of("核实身份")));var hypothesis=p.nodes().stream().filter(n->n.kind()==Kind.HYPOTHESIS).findFirst().orElseThrow();
        assertTrue(p.edges().stream().filter(e->e.to().equals(hypothesis.id())).allMatch(e->e.relation()==Relation.HAS_HYPOTHESIS));
    }
    @Test void repeatedStepsKeepSeparateOccurrenceEvidenceAndOrder(){
        var p=compile(card("手机号可用",List.of("核实身份","核实身份","设置新密码")));
        assertEquals(3,p.evidence().stream().filter(e->e.details().field().equals("steps")).count());
        assertEquals(2,p.evidence().stream().filter(e->e.details().field().equals("stepOrder")).count());
        assertTrue(p.edges().stream().anyMatch(e->e.relation()==Relation.NEXT_STEP&&e.from().equals(e.to())));
    }
    @Test void stepOrderChangesGraphHash(){assertNotEquals(compile(card("手机号可用",List.of("核实身份","设置新密码"))).graphHash(),
            compile(card("手机号可用",List.of("设置新密码","核实身份"))).graphHash());}
    @Test void outcomeNeverFabricatesCustomerSuccess(){
        var p=compile(card("手机号可用",List.of("核实身份")));var outcomes=p.nodes().stream().filter(n->n.kind()==Kind.OUTCOME).toList();
        assertEquals(1,outcomes.size());assertEquals("业务效果待核实（非独立验证）",outcomes.get(0).label());
    }
    @Test void eachRelationHasFieldPositionScopeAndReferences(){
        var c=card("手机号可用",List.of("核实身份"));var p=compile(c);
        assertTrue(p.evidence().stream().allMatch(s->!s.details().references().isEmpty()&&s.details().ordinal()>=0&&s.details().factScope().equals(c.factScope().name())));
        assertEquals(p.edges().size(),p.evidence().stream().map(Support::edgeId).distinct().count());
    }
    @Test void tamperedCardHashOrRenderedTextCannotBuildGraph(){
        var c=stored(card("手机号可用",List.of("核实身份")));
        assertThrows(IllegalStateException.class,()->compiler.compile(new StoredCard(c.id(),"0".repeat(64),c.content(),c.question(),c.answer()),UUID.randomUUID().toString()));
        assertThrows(IllegalStateException.class,()->compiler.compile(new StoredCard(c.id(),c.hash(),c.content(),c.question(),"篡改后的自由文本"),UUID.randomUUID().toString()));
    }
    @Test void unverifiedCardDoesNotBecomePublishedGraph(){
        var c=card("手机号可用",List.of("核实身份"));c=new Card(c.problemType(),c.symptoms(),c.conditions(),c.steps(),c.contraindications(),c.counterexamples(),c.hypotheses(),c.evidenceRefs(),c.factScope(),SupportStatus.UNVERIFIED);
        Card unknown=c;assertThrows(IllegalStateException.class,()->compile(unknown));
    }
    @Test void graphBoundIsSmallerThanStoreEvidenceLimit(){
        var c=card("手机号可用",List.of("步骤1","步骤2","步骤3","步骤4","步骤5","步骤6","步骤7","步骤8"));
        assertTrue(compile(c).evidence().size()<128);
    }
}
