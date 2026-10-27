package com.smartticket.agent;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentAnswerTest {
    @org.junit.jupiter.api.Test void graphCitationAlwaysRendersSyntheticScopeWithoutChangingSource() throws Exception {
        var answer=AgentAnswer.parse("{\"answer\":\"先核实原手机号能接收短信，再验证身份\",\"needHuman\":false,\"sourceIds\":[\"demo#0\"]}",java.util.Set.of("demo#0"),true);
        String rendered=answer.render(java.util.Set.of("demo#0"));
        org.junit.jupiter.api.Assertions.assertTrue(rendered.contains("合成演示"));org.junit.jupiter.api.Assertions.assertTrue(rendered.contains("效果未独立验证"));
        org.junit.jupiter.api.Assertions.assertTrue(rendered.endsWith("来源：demo#0"));
    }
    private final Set<String> available = Set.of("faq/pay#0");
    @Test void acceptsValidGroundedAnswer() throws Exception {
        var value = AgentAnswer.parse("{\"answer\":\"核对流水\",\"needHuman\":false,\"sourceIds\":[\"faq/pay#0\"]}", available, true);
        assertFalse(value.needHuman());
        assertEquals("核对流水", value.answer());
    }
    @Test void preservesModelNeedForHuman() throws Exception {
        assertTrue(AgentAnswer.parse("{\"answer\":\"资料不足，人工核实\",\"needHuman\":true,\"sourceIds\":[],\"handoff\":{\"state\":\"INSUFFICIENT_EVIDENCE\",\"reason\":\"CRITICAL_FACTS_MISSING\",\"detail\":\"缺少该笔退款实际到账记录\"}}", available, true).needHuman());
    }
    @Test void missingCitationAfterSearchRequiresHuman() throws Exception {
        assertTrue(AgentAnswer.parse("{\"answer\":\"声称完成\",\"needHuman\":false,\"sourceIds\":[]}", available, true).needHuman());
    }
    @Test void rejectsMadeUpSources() {
        assertThrows(Exception.class, () -> AgentAnswer.parse("{\"answer\":\"建议\",\"needHuman\":false,\"sourceIds\":[\"not-provided\"]}", available, true));
    }
    @Test void greetingCanCompleteWithoutKnowledge() throws Exception {
        assertFalse(AgentAnswer.parse("{\"answer\":\"你好\",\"needHuman\":false,\"sourceIds\":[]}", Set.of(), false).needHuman());
    }
    @Test void rejectsPlainTextAndStringBoolean() {
        assertThrows(Exception.class, () -> AgentAnswer.parse("已经完成", available, true));
        assertThrows(Exception.class, () -> AgentAnswer.parse("{\"answer\":\"建议\",\"needHuman\":\"false\",\"sourceIds\":[]}", available, true));
    }
    @Test void rejectsMissingExtraDuplicateFieldsAndTrailingJson() {
        for (String text : new String[]{"{}", "{\"answer\":\"建议\",\"needHuman\":false,\"sourceIds\":[],\"extra\":1}",
                "{\"answer\":\"建议\",\"needHuman\":true,\"needHuman\":false,\"sourceIds\":[]}",
                "{\"answer\":\"建议\",\"needHuman\":false,\"sourceIds\":[]} {}"})
            assertThrows(Exception.class, () -> AgentAnswer.parse(text, available, false));
    }
    @Test void rejectsEmptyAndOversizedAnswer() {
        assertThrows(Exception.class, () -> AgentAnswer.parse("{\"answer\":\" \",\"needHuman\":false,\"sourceIds\":[]}", available, false));
        assertThrows(Exception.class, () -> AgentAnswer.parse("{\"answer\":\""+"文".repeat(4001)+"\",\"needHuman\":false,\"sourceIds\":[]}", available, false));
    }
    @Test void rendersValidatedSourcesAndBoundsAnswer() throws Exception {
        var result=AgentAnswer.parse("{\"answer\":\""+"文".repeat(4000)+"\",\"needHuman\":false,\"sourceIds\":[\"faq/pay#0\"]}",available,true);
        assertEquals(4000,result.render().length());
        assertTrue(result.render().endsWith("来源：faq/pay#0"));
    }
    @Test void clarificationCanAskForMissingUserContextWithoutCitationOrHandoff()throws Exception {
        var a=AgentAnswer.parse("{\"answer\":\"原手机号还能接收短信吗？\",\"needHuman\":false,\"sourceIds\":[],\"handoff\":{\"state\":\"NEEDS_CLARIFICATION\",\"reason\":\"USER_CONTEXT_REQUIRED\",\"detail\":\"原手机号是否仍能接收短信尚未说明\"}}",available,true);
        assertFalse(a.needHuman());assertTrue(a.handoff().clarification());
    }
    @Test void completeExplanationWithHumanFlagIsRejectedForRepair() {
        assertThrows(com.smartticket.assistance.HandoffDecision.Rejected.class,()->AgentAnswer.parse("{\"answer\":\"SENT只是已投递\",\"needHuman\":true,\"sourceIds\":[\"faq/pay#0\"],\"handoff\":{\"state\":\"ANSWERED\",\"reason\":\"NONE\",\"detail\":\"\"}}",available,true));
    }
    @Test void missingHumanReasonCannotPassAsAnUnrestrictedBoolean() {
        assertThrows(com.smartticket.assistance.HandoffDecision.Rejected.class,()->AgentAnswer.parse("{\"answer\":\"只因演示范围请人工核实\",\"needHuman\":true,\"sourceIds\":[\"faq/pay#0\"]}",available,true));
    }
    @Test void missingCriticalFactsKeepSpecificReasonEvenWithoutCitation()throws Exception {
        var a=AgentAnswer.parse("{\"answer\":\"不能确认到账时刻\",\"needHuman\":true,\"sourceIds\":[],\"handoff\":{\"state\":\"INSUFFICIENT_EVIDENCE\",\"reason\":\"CRITICAL_FACTS_MISSING\",\"detail\":\"缺少该笔退款的银行实际处理记录\"}}",available,true);
        assertEquals("缺少该笔退款的银行实际处理记录",a.handoff().detail());
    }
}
