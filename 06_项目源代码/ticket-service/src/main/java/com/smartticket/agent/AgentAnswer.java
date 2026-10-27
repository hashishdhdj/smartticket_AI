package com.smartticket.agent;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import com.smartticket.assistance.HandoffDecision;

/** Strict final-message contract; tool completion alone does not establish semantic success. */
public record AgentAnswer(String answer, boolean needHuman, List<String> sourceIds, HandoffDecision handoff) {
    public AgentAnswer(String answer,boolean needHuman,List<String> sourceIds) {
        this(answer,needHuman,sourceIds,needHuman?HandoffDecision.noEvidence():HandoffDecision.answered());
    }
    public String render() {
        return render(Set.of());
    }
    public String render(Set<String> graphSources) {
        if (sourceIds.isEmpty()) return answer;
        String footer = (sourceIds.stream().anyMatch(graphSources::contains)
                ? "\n经验范围：已审核合成演示资料；模拟平台规则不代表本项目已实现功能。请按适用条件使用，业务效果未独立验证。" : "")
                + "\n来源：" + String.join("、", sourceIds);
        int room = Math.max(0, 4000 - footer.length());
        return answer.substring(0, Math.min(answer.length(), room)) + footer;
    }
    public static AgentAnswer parse(String text, Set<String> available, boolean searched) throws Exception {
        if (text == null || text.length() > 12000) throw new IllegalArgumentException("Invalid final output");
        var mapper = JsonMapper.builder()
                .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        var node = mapper.readTree(text);
        if (node == null || !node.isObject() || (node.size() != 3 && node.size()!=4)
                || (node.size()==4 && !node.has("handoff")) || !node.has("answer")
                || !node.has("needHuman") || !node.has("sourceIds")
                || !node.get("answer").isTextual() || !node.get("needHuman").isBoolean()
                || !node.get("sourceIds").isArray()) throw new IllegalArgumentException("Invalid final schema");
        String answer = node.get("answer").textValue().strip();
        if (answer.isEmpty() || answer.length() > 4000 || node.get("sourceIds").size() > 6)
            throw new IllegalArgumentException("Final output bounds");
        List<String> ids = new ArrayList<>();
        for (var id : node.get("sourceIds")) {
            if (!id.isTextual() || id.textValue().length() > 512 || !available.contains(id.textValue()) || ids.contains(id.textValue()))
                throw new IllegalArgumentException("Ungrounded source reference");
            ids.add(id.textValue());
        }
        boolean needHuman = node.get("needHuman").booleanValue();
        var handoff=HandoffDecision.parse(node.get("handoff"),needHuman);
        if (searched && ids.isEmpty() && handoff.state()==HandoffDecision.State.ANSWERED) handoff=HandoffDecision.noEvidence();
        return new AgentAnswer(answer, handoff.needHuman(), List.copyOf(ids),handoff);
    }
}
