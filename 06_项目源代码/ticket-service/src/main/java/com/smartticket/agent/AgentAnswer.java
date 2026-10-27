package com.smartticket.agent;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;

/** Strict final-message contract; tool completion alone does not establish semantic success. */
public record AgentAnswer(String answer, boolean needHuman, List<String> sourceIds) {
    public String render() {
        return render(Set.of());
    }
    public String render(Set<String> graphSources) {
        if (sourceIds.isEmpty()) return answer;
        String footer = (sourceIds.stream().anyMatch(graphSources::contains)
                ? "\n经验范围：已审核合成演示资料；模拟平台规则不代表本项目已实现功能。适用前提仍需核实，业务效果未独立验证。" : "")
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
        if (node == null || !node.isObject() || node.size() != 3 || !node.has("answer")
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
        if (searched && ids.isEmpty()) needHuman = true;
        return new AgentAnswer(answer, needHuman, List.copyOf(ids));
    }
}
