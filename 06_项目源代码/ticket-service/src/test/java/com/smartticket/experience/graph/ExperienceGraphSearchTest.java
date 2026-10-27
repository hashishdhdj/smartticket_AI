package com.smartticket.experience.graph;

import com.smartticket.exception.ApiFailure;
import com.smartticket.experience.ExperienceCardCodec;
import com.smartticket.experience.ExperienceCardContract.*;
import com.smartticket.experience.ExperienceContract.Evidence;
import com.smartticket.common.JsonCodec;
import com.smartticket.knowledge.KnowledgeSource;
import com.smartticket.rag.*;
import com.smartticket.security.ActorContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.smartticket.experience.graph.ExperienceGraph.*;

class ExperienceGraphSearchTest {
    final KnowledgeSearchService knowledge=mock(KnowledgeSearchService.class);
    final ExperienceGraphReader reader=mock(ExperienceGraphReader.class);
    final ExperienceGraphSearchService search=new ExperienceGraphSearchService(knowledge,reader);
    final KnowledgeSource key=new KnowledgeSource(UUID.randomUUID().toString(),1);
    final JsonCodec json=new JsonCodec(new ObjectMapper());
    static final jakarta.validation.ValidatorFactory FACTORY=jakarta.validation.Validation.buildDefaultValidatorFactory();
    final ExperienceCardCodec codec=new ExperienceCardCodec(json,FACTORY.getValidator());
    final ExperienceGraphCompiler compiler=new ExperienceGraphCompiler(json,codec);
    @BeforeEach void setup(){ActorContext.set(new ActorContext.Actor(100,"SUBMITTER"));when(reader.enabled()).thenReturn(true);}
    @AfterEach void clear(){ActorContext.clear();}
    @AfterAll static void close(){FACTORY.close();}
    private Snapshot snapshot(String condition) {
        var c=codec.normalize(new Card("合成密码重置经验",List.of("忘记登录口令"),List.of(condition),List.of("核对原手机号","完成验证","核对原手机号"),
                List.of("原手机号停用时不得沿用"),List.of("错误尝试：绕过验证码"),List.of("可能输入有误，未证实"),
                List.of(new Evidence("faq/01-账户问题.md","模拟重置资料")),FactScope.SIMULATED_PLATFORM_RULE_NOT_IMPLEMENTED_FEATURE,SupportStatus.REFERENCED_DEMO));
        var p=compiler.compile(new StoredCard(UUID.randomUUID().toString(),codec.hash(c),c,c.problemType(),codec.answer(c)),UUID.randomUUID().toString());
        var m=new Manifest(key.candidateId(),1,key.file(),"ACTIVE",p.cardId(),p.submissionId(),p.cardHash(),p.textHash(),p.graphHash(),COMPILER,p.factScope(),p.evidence().size());
        return new Snapshot(m,p.nodes(),p.edges(),p.evidence());
    }
    private FusedHit hit(String file){return new FusedHit(new KnowledgeChunk(file,"经验","问题",0,"不可信检索正文"),0.02,Set.of(RetrievalSource.KEYWORD));}
    private void seeds(List<FusedHit> hits){when(knowledge.search(anyString())).thenReturn(new KnowledgeSearchResult(hits,List.of(),1));}
    private List<?> sources(Map<String,Object> result){return (List<?>)result.get("sources");}
    @Test void unauthenticatedCallsNeverReachRetrieval(){ActorContext.clear();assertThrows(ApiFailure.class,()->search.search("问题"));verifyNoInteractions(knowledge);}
    @Test void disabledFeatureNeverSearches(){when(reader.enabled()).thenReturn(false);assertEquals(404,assertThrows(ApiFailure.class,()->search.search("问题")).status());verifyNoInteractions(knowledge);}
    @Test void validatesQueryBeforeIo(){for(String q:Arrays.asList(null," ","x".repeat(1001)))assertThrows(ApiFailure.class,()->search.search(q));verifyNoInteractions(knowledge);}
    @Test void ordinaryFaqCannotBeInventedAsGraph(){seeds(List.of(hit("faq/01-账户问题.md")));assertTrue(sources(search.search("问题")).isEmpty());verify(reader,never()).read(any());}
    @Test void unapprovedOrUnreadySourcesDoNotSupplyFacts(){seeds(List.of(hit(key.file())));when(reader.read(key)).thenReturn(Optional.empty());assertTrue(sources(search.search("问题")).isEmpty());}
    @Test void verifiedPgGraphRatherThanHitExcerptSuppliesFacts(){seeds(List.of(hit(key.file())));when(reader.read(key)).thenReturn(Optional.of(snapshot("原手机可收短信")));
        var row=(Map<?,?>)sources(search.search("问题")).get(0);assertEquals(key.file()+"#0",row.get("sourceId"));assertFalse(row.toString().contains("不可信检索正文"));assertEquals(SCOPE,row.get("scope"));}
    @Test void prerequisitesNegationStepsCounterexamplesHypothesesAndUnknownOutcomeSurvive(){var row=ExperienceGraphSearchService.describe(key,snapshot("原手机仍可用且未停用")).orElseThrow();var facts=(Map<?,?>)row.get("facts");
        assertEquals(List.of("原手机仍可用且未停用"),facts.get("conditions"));assertEquals(List.of("核对原手机号","完成验证","核对原手机号"),facts.get("steps"));
        assertTrue(facts.get("contraindications").toString().contains("不得"));assertTrue(facts.get("counterexamples").toString().contains("错误"));assertTrue(facts.get("hypotheses").toString().contains("未证实"));assertTrue(facts.get("outcome").toString().contains("待核实"));}
    @Test void everyPathHasEvidenceAndTheWholeResponseHasOneSource(){var row=ExperienceGraphSearchService.describe(key,snapshot("可收短信")).orElseThrow();var paths=(List<?>)row.get("paths");
        assertFalse(paths.isEmpty());for(Object p:paths)assertFalse(((List<?>)((Map<?,?>)p).get("evidenceKeys")).isEmpty());assertEquals(1L,row.get("revision"));}
    @Test void repeatedStepOrderIsRepresentedByOccurrence(){var row=ExperienceGraphSearchService.describe(key,snapshot("可收短信")).orElseThrow();String paths=row.get("contextPaths").toString();assertTrue(paths.contains("steps/0, NEXT_STEP, steps/1"));assertTrue(paths.contains("steps/1, NEXT_STEP, steps/2"));}
    @Test void repeatedHitsDoNotDuplicateGraphReads(){seeds(List.of(hit(key.file()),hit(key.file())));when(reader.read(key)).thenReturn(Optional.of(snapshot("可收短信")));assertEquals(1,sources(search.search("问题")).size());verify(reader,times(1)).read(key);}
    @Test void sourcesStaySeparateEvenWithSharedNodes(){var other=new KnowledgeSource(UUID.randomUUID().toString(),1);seeds(List.of(hit(key.file()),hit(other.file())));
        when(reader.read(key)).thenReturn(Optional.of(snapshot("原手机可用")));when(reader.read(other)).thenReturn(Optional.empty());assertEquals(1,sources(search.search("问题")).size());}
    @Test void unavailableGraphHasExplicitDegradationAndNoDatabaseDetails(){seeds(List.of(hit(key.file())));when(reader.read(key)).thenThrow(new IllegalStateException("password=secret SQL text"));var result=search.search("问题");assertTrue(sources(result).isEmpty());assertTrue(result.get("degraded").toString().contains("GRAPH_UNAVAILABLE"));assertFalse(result.toString().contains("secret"));}
    @Test void sourceRevalidationRequiresExactSourceAndHash(){var graph=snapshot("可收短信");when(reader.read(key)).thenReturn(Optional.of(graph));assertTrue(search.current(key.file()+"#0",graph.publication().graphHash()));assertFalse(search.current(key.file()+"#0","wrong"));assertFalse(search.current(key.file()+"#1",graph.publication().graphHash()));assertFalse(search.current(graph.nodes().get(0).id(),graph.publication().graphHash()));}
    @Test void oversizedGraphIsOmittedRatherThanLosingItsConditions(){var s=snapshot("条件");var nodes=new ArrayList<>(s.nodes());for(int i=0;i<50;i++)nodes.add(new Node("extra"+i,Kind.CONDITION,"key","label"));assertTrue(ExperienceGraphSearchService.describe(key,new Snapshot(s.publication(),nodes,s.edges(),s.evidence())).isEmpty());}
    @Test void disconnectedGraphCannotReturnPartialPrerequisites(){var s=snapshot("条件");var nodes=new ArrayList<>(s.nodes());nodes.add(new Node("orphan",Kind.CONDITION,"key","必须保留"));assertTrue(ExperienceGraphSearchService.describe(key,new Snapshot(s.publication(),nodes,s.edges(),s.evidence())).isEmpty());}
}
