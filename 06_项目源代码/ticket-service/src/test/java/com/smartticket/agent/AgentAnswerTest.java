package com.smartticket.agent;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentAnswerTest {
    private final Set<String> available = Set.of("faq/pay#0");
    @Test void acceptsValidGroundedAnswer() throws Exception {
        var value = AgentAnswer.parse("{\"answer\":\"核对流水\",\"needHuman\":false,\"sourceIds\":[\"faq/pay#0\"]}", available, true);
        assertFalse(value.needHuman());
        assertEquals("核对流水", value.answer());
    }
    @Test void preservesModelNeedForHuman() throws Exception {
        assertTrue(AgentAnswer.parse("{\"answer\":\"资料不足，人工核实\",\"needHuman\":true,\"sourceIds\":[]}", available, true).needHuman());
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
}
