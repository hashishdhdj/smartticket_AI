package com.smartticket.agent;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentToolContextTest {
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
