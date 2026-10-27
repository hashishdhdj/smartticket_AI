package com.smartticket.assistance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HandoffDecisionTest {
    final ObjectMapper json=new ObjectMapper();
    HandoffDecision parse(String value,boolean flag)throws Exception{return HandoffDecision.parse(json.readTree(value),flag);}
    @Test void completedExplanationIsAnAnswerNotAHandoff()throws Exception {
        var d=parse("{\"state\":\"ANSWERED\",\"reason\":\"NONE\",\"detail\":\"\"}",false);
        assertFalse(d.needHuman());assertEquals("ANSWER",d.nextAction());
    }
    @Test void completedExplanationCannotCarryTrueHandoffFlag() {
        assertThrows(HandoffDecision.Rejected.class,()->parse("{\"state\":\"ANSWERED\",\"reason\":\"NONE\",\"detail\":\"\"}",true));
    }
    @Test void clarificationDoesNotRequireAnOperator()throws Exception {
        var d=parse("{\"state\":\"NEEDS_CLARIFICATION\",\"reason\":\"USER_CONTEXT_REQUIRED\",\"detail\":\"请补充原手机号是否仍能接收短信\"}",false);
        assertFalse(d.needHuman());assertEquals("ASK_USER",d.nextAction());
    }
    @Test void clarificationCannotSilentlyBecomeHumanEscalation() {
        assertThrows(HandoffDecision.Rejected.class,()->parse("{\"state\":\"NEEDS_CLARIFICATION\",\"reason\":\"USER_CONTEXT_REQUIRED\",\"detail\":\"请补充当前使用的接口版本\"}",true));
    }
    @Test void actualMissingRefundRecordPreservesHandoff()throws Exception {
        var d=parse("{\"state\":\"INSUFFICIENT_EVIDENCE\",\"reason\":\"CRITICAL_FACTS_MISSING\",\"detail\":\"缺少这笔退款的银行实际处理记录\"}",true);
        assertTrue(d.needHuman());assertEquals("HUMAN_SUPPORT",d.nextAction());
    }
    @Test void requestedHumanOperationPreservesHandoff()throws Exception {
        assertTrue(parse("{\"state\":\"HUMAN_ACTION_REQUIRED\",\"reason\":\"HUMAN_OPERATION_REQUIRED\",\"detail\":\"用户要求实际办理旧号停用后的人工身份核验\"}",true).needHuman());
    }
    @Test void criticalFactsCannotBeDeclaredMissingWhileClaimingNoHandoff() {
        assertThrows(HandoffDecision.Rejected.class,()->parse("{\"state\":\"INSUFFICIENT_EVIDENCE\",\"reason\":\"CRITICAL_FACTS_MISSING\",\"detail\":\"缺少实际到账记录和退款处理状态\"}",false));
    }
    @Test void demoScopeAndUnverifiedOutcomeAreNotAllowedReasonCategories() {
        for(String reason:new String[]{"DEMO_SCOPE","UNVERIFIED_OUTCOME","MISSING_IMPLEMENTATION_DETAILS"})
            assertThrows(HandoffDecision.Rejected.class,()->parse("{\"state\":\"INSUFFICIENT_EVIDENCE\",\"reason\":\""+reason+"\",\"detail\":\"演示数据业务效果尚未验证\"}",true));
    }
    @Test void trueHandoffMustProvideItsSpecificDecision() {assertThrows(HandoffDecision.Rejected.class,()->HandoffDecision.parse(null,true));}
    @Test void legacySuccessfulResponsesRemainCompatible() {assertEquals(HandoffDecision.answered(),HandoffDecision.parse(null,false));}
    @Test void blankOversizedOrMismatchedBlockersAreRejected() {
        assertThrows(HandoffDecision.Rejected.class,()->new HandoffDecision(HandoffDecision.State.INSUFFICIENT_EVIDENCE,HandoffDecision.Reason.CRITICAL_FACTS_MISSING,""));
        assertThrows(HandoffDecision.Rejected.class,()->new HandoffDecision(HandoffDecision.State.INSUFFICIENT_EVIDENCE,HandoffDecision.Reason.NONE,"缺少退款实际处理记录"));
        assertThrows(HandoffDecision.Rejected.class,()->new HandoffDecision(HandoffDecision.State.INSUFFICIENT_EVIDENCE,HandoffDecision.Reason.CRITICAL_FACTS_MISSING,"字".repeat(201)));
    }
    @Test void unknownExtraOrNonTextDecisionFieldsAreRejected() {
        for(String value:new String[]{"null","{}","{\"state\":true,\"reason\":\"NONE\",\"detail\":\"\"}","{\"state\":\"ANSWERED\",\"reason\":\"NONE\",\"detail\":\"\",\"role\":\"ADMIN\"}"})
            assertThrows(HandoffDecision.Rejected.class,()->parse(value,false));
    }
}
