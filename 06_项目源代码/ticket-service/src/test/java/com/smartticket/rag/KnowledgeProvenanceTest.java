package com.smartticket.rag;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeProvenanceTest {
    @Test
    void continuationOfReviewedSimulationDoesNotBecomeProjectFact() {
        assertEquals("SIMULATED_SCENARIO", KnowledgeProvenance.scope("tickets/02-支付问题.md", "解决方案的后半部分"));
    }

    @Test
    void projectRulesAndUnknownSourcesAreDistinguished() {
        assertEquals("PROJECT_REFERENCE", KnowledgeProvenance.scope("faq/05-项目权限与AI处理.md", "权限规则"));
        assertEquals("UNSPECIFIED", KnowledgeProvenance.scope("new-source.md", "未登记正文"));
    }

    @Test
    void explicitSimulationScopeRemainsRecognizedForNewSources() {
        assertEquals("SIMULATED_SCENARIO", KnowledgeProvenance.scope("new-source.md", "适用范围：模拟客服平台场景"));
    }
}
