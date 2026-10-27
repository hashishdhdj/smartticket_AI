package com.smartticket.assistance;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartticket.ai.AiResponseInvalidException;
import java.util.*;

/** A clarification or an answered explanation is not a human handoff. No business authority is granted here. */
public record HandoffDecision(State state, Reason reason, String detail) {
    public enum State { ANSWERED, NEEDS_CLARIFICATION, INSUFFICIENT_EVIDENCE, HUMAN_ACTION_REQUIRED }
    public enum Reason { NONE, USER_CONTEXT_REQUIRED, CRITICAL_FACTS_MISSING, HUMAN_OPERATION_REQUIRED }
    public static final String REPAIR_INSTRUCTION = "上一条人工升级判定不符合契约，请只基于已有资料重新输出一次完整JSON，不再调用工具。"
            + "handoff必须包含state、reason、detail。规则解释、PUT/版本/权限/SENT/审核与索引区别等，已有依据就用ANSWERED/NONE/空detail且needHuman=false。"
            + "不能仅因演示范围、效果未实测、缺少本题不要求的实现细节转人工。用户可补信息用NEEDS_CLARIFICATION/USER_CONTEXT_REQUIRED且needHuman=false，提出具体追问。"
            + "确实缺少本题关键业务事实才用INSUFFICIENT_EVIDENCE/CRITICAL_FACTS_MISSING；用户要求实际人工办理才用HUMAN_ACTION_REQUIRED/HUMAN_OPERATION_REQUIRED；"
            + "这两种needHuman=true且detail写明具体缺失事实或人工操作。不要编造执行结果，不扩写截断资料。";

    public static final class Rejected extends AiResponseInvalidException {
        public Rejected(String message) { super(message); }
    }

    public HandoffDecision {
        if (state==null || reason==null || detail==null || detail.length()>200)
            throw new Rejected("人工升级决策字段无效");
        detail=detail.strip();
        Reason expected=switch(state) {
            case ANSWERED -> Reason.NONE;
            case NEEDS_CLARIFICATION -> Reason.USER_CONTEXT_REQUIRED;
            case INSUFFICIENT_EVIDENCE -> Reason.CRITICAL_FACTS_MISSING;
            case HUMAN_ACTION_REQUIRED -> Reason.HUMAN_OPERATION_REQUIRED;
        };
        if (reason!=expected || (state==State.ANSWERED ? !detail.isEmpty() : detail.length()<5))
            throw new Rejected("人工升级状态、原因或具体阻塞说明不一致");
    }

    public boolean needHuman() { return state==State.INSUFFICIENT_EVIDENCE || state==State.HUMAN_ACTION_REQUIRED; }
    public boolean clarification() { return state==State.NEEDS_CLARIFICATION; }
    public String nextAction() { return needHuman()?"HUMAN_SUPPORT":clarification()?"ASK_USER":"ANSWER"; }

    public static HandoffDecision answered() { return new HandoffDecision(State.ANSWERED,Reason.NONE,""); }
    public static HandoffDecision clarification(String detail) {
        return new HandoffDecision(State.NEEDS_CLARIFICATION,Reason.USER_CONTEXT_REQUIRED,detail);
    }
    public static HandoffDecision noEvidence() {
        return new HandoffDecision(State.INSUFFICIENT_EVIDENCE,Reason.CRITICAL_FACTS_MISSING,"未提供可支持当前知识回答的有效来源");
    }
    public void validateFlag(boolean declared) {
        if (declared!=needHuman()) throw new Rejected("已回答或待澄清任务不得触发人工升级，真实人工事项不得伪装成直接完成");
    }

    public static HandoffDecision parse(JsonNode node,boolean declared) {
        if (node==null) {
            if (declared) throw new Rejected("转人工必须提供结构化原因和具体阻塞事项");
            return answered(); // Compatible with existing successful three-field responses.
        }
        if (!node.isObject() || node.size()!=3 || !node.path("state").isTextual()
                || !node.path("reason").isTextual() || !node.path("detail").isTextual())
            throw new Rejected("handoff必须且只能包含state、reason、detail");
        try {
            var decision=new HandoffDecision(State.valueOf(node.get("state").textValue()),
                    Reason.valueOf(node.get("reason").textValue()),node.get("detail").textValue());
            decision.validateFlag(declared);
            return decision;
        } catch (IllegalArgumentException invalid) {
            throw new Rejected("未允许的人工升级状态或原因");
        }
    }
}
