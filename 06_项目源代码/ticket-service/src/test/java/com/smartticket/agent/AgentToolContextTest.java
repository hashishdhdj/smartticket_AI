package com.smartticket.agent;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentToolContextTest {
    private Map<String,Object> graphSource(String id,String condition) {
        return Map.of("sourceId",id,"scope","SHARED_DEMO","factScope","SIMULATED_PLATFORM_RULE_NOT_IMPLEMENTED_FEATURE",
                "graphHash","hash","facts",Map.of("conditions",List.of(condition),"steps",List.of("验证身份"),"contraindications",List.of("身份不明不得执行")),
                "contextPaths",List.of(List.of("problem","REQUIRES","conditions/0","conditions/0")),
                "notice","仅限演示，效果待核实","nodes",List.of("large audit only"));
    }
    @Test void graphContextKeepsCompleteConditionsAndScopeButOmitsRawAudit() {
        var provided=new HashSet<String>();var graphs=new HashSet<String>();
        var view=(Map<?,?>)AgentToolContext.compact("searchExperienceGraph",Map.of("sources",List.of(graphSource("demo#0","原手机未停用且可接收验证码"))),provided,graphs);
        var row=(Map<?,?>)((List<?>)view.get("sources")).get(0);
        assertEquals("SHARED_DEMO",row.get("scope"));assertTrue(row.get("facts").toString().contains("未停用"));assertFalse(row.containsKey("nodes"));
        assertFalse((Boolean)row.get("truncated"));assertEquals(Set.of("demo#0"),provided);assertEquals(provided,graphs);
    }
    @Test void oversizedGraphIsOmittedAtomicallyAndNeverBecomesCitable() {
        var provided=new HashSet<String>();var view=(Map<?,?>)AgentToolContext.compact("searchExperienceGraph",
                Map.of("sources",List.of(graphSource("demo#0","不能".repeat(4000)))),provided);
        assertEquals(List.of(),view.get("sources"));assertEquals(1,view.get("omittedSources"));assertTrue(provided.isEmpty());
    }
    @Test void secondGraphCannotDisplaceItsNegativeFactsToFit() {
        var first=graphSource("first#0","未停用".repeat(480));var second=graphSource("second#0","不得".repeat(480));var provided=new HashSet<String>();
        var view=(Map<?,?>)AgentToolContext.compact("searchExperienceGraph",Map.of("sources",List.of(first,second)),provided);
        assertEquals(1,((List<?>)view.get("sources")).size());assertEquals(Set.of("first#0"),provided);
    }
    @Test void graphViewIsDeliveredEvenIfTextForSameSourceWasAlreadyProvided() {
        var provided=new HashSet<>(Set.of("demo#0"));var graph=new HashSet<String>();
        var result=Map.of("sources",List.of(graphSource("demo#0","未停用")));
        var view=(Map<?,?>)AgentToolContext.compact("searchExperienceGraph",result,provided,graph);
        assertTrue(((Map<?,?>)((List<?>)view.get("sources")).get(0)).containsKey("facts"));
        var repeat=(Map<?,?>)AgentToolContext.compact("searchExperienceGraph",result,provided,graph);
        assertEquals(List.of(Map.of("sourceId","demo#0","alreadyProvided",true)),repeat.get("sources"));
    }
    @Test void graphNodesAndRelationsNeverBecomeAvailableCitationIds() {
        var provided=new HashSet<String>();AgentToolContext.compact("searchExperienceGraph",Map.of("sources",List.of(graphSource("demo#0","未停用"))),provided);
        assertEquals(Set.of("demo#0"),provided);assertFalse(provided.contains("REQUIRES"));
    }
    @Test
    void continuationSourceKeepsSimulationScopeWithoutPreamble() {
        var original = Map.of("sources", List.of(Map.of("sourceId", "tickets/02-支付问题.md#1", "title", "续片", "excerpt", "处理步骤的后半部分")));
        var view = (Map<?, ?>) AgentToolContext.compact("searchKnowledge", original, new HashSet<>());
        var row = (Map<?, ?>) ((List<?>) view.get("sources")).get(0);
        assertEquals("SIMULATED_SCENARIO", row.get("scope"));
    }
    @Test
    void boundsUtf8WithoutBreakingCharactersAndKeepsSimulationScope() {
        String full = "**适用范围**：模拟客服平台场景，不代表真实能力。\n" + "步骤😀处理问题。".repeat(100);
        var source = Map.of("sourceId", "faq/example#0", "title", "模拟问题", "excerpt", full);
        var original = Map.of("sources", List.of(source), "degraded", List.of());
        var view = (Map<?, ?>) AgentToolContext.compact("searchKnowledge", original, new HashSet<>());
        var row = (Map<?, ?>) ((List<?>) view.get("sources")).get(0);
        String excerpt = (String) row.get("excerpt");
        assertTrue(excerpt.getBytes(StandardCharsets.UTF_8).length <= 1400);
        assertEquals(excerpt, new String(excerpt.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertEquals("SIMULATED_SCENARIO", row.get("scope"));
        assertEquals(true, row.get("truncated"));
        assertEquals(full, source.get("excerpt"), "原审计证据不能被压缩视图修改");
    }

    @Test
    void repeatedSourceReferencesPreviousEvidenceWithoutRepeatingTheBody() {
        var provided = new HashSet<String>();
        var original = Map.of("sources", List.of(Map.of("sourceId", "faq/example#0", "title", "问题", "excerpt", "正文")));
        AgentToolContext.compact("searchKnowledge", original, provided);
        var second = (Map<?, ?>) AgentToolContext.compact("searchKnowledge", original, provided);
        assertEquals(List.of(Map.of("sourceId", "faq/example#0", "alreadyProvided", true)), second.get("sources"));
    }

    @Test
    void otherToolResultsAreNotRewritten() {
        var original = Map.of("ticketId", 123, "actionId", "action");
        assertSame(original, AgentToolContext.compact("prepareAssignTicket", original, new HashSet<>()));
    }

    @Test void prioritizesCompleteFirstSourceWithinSharedBudget() {
        String full = "核对扣款凭据。".repeat(25) + "记录交易流水号，金额与时间；不要重复下单。";
        var rows = List.of(
                Map.of("sourceId", "faq/one#0", "title", "支付", "excerpt", full),
                Map.of("sourceId", "faq/two#0", "title", "案例", "excerpt", "案例内容".repeat(300)),
                Map.of("sourceId", "faq/three#0", "title", "补充", "excerpt", "补充内容".repeat(300)));
        var view = (Map<?, ?>) AgentToolContext.compact("searchKnowledge", Map.of("sources", rows), new HashSet<>());
        var compact = (List<?>) view.get("sources");
        var first = (Map<?, ?>) compact.get(0);
        assertEquals(false, first.get("truncated"));
        assertTrue(first.get("excerpt").toString().contains("交易流水号"));
        int bytes = compact.stream().map(row -> (Map<?, ?>) row)
                .mapToInt(row -> row.get("excerpt").toString().getBytes(StandardCharsets.UTF_8).length).sum();
        assertTrue(bytes <= 2200);
    }
}
