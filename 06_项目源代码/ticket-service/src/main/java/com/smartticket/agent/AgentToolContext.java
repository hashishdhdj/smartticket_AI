package com.smartticket.agent;

import com.smartticket.rag.KnowledgeProvenance;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded model context; the API/audit keeps the original tool evidence. */
public final class AgentToolContext {
    static final int EXCERPT_BYTES = 1400;

    private AgentToolContext() {}

    public static Object compact(String tool, Object result, Set<String> providedSources) {
        if (!"searchKnowledge".equals(tool) || !(result instanceof Map<?, ?> outer)
                || !(outer.get("sources") instanceof List<?> sources)) return result;
        Map<String, Object> view = new LinkedHashMap<>();
        outer.forEach((key, value) -> view.put(String.valueOf(key), value));
        List<Object> compact = new ArrayList<>();
        int remaining = 2200;
        for (Object source : sources) {
            if (!(source instanceof Map<?, ?> row)) continue;
            String id = Objects.toString(row.get("sourceId"), "");
            if (!id.isEmpty() && !providedSources.add(id)) {
                compact.add(Map.of("sourceId", id, "alreadyProvided", true));
                continue;
            }
            String full = Objects.toString(row.get("excerpt"), "");
            String sourceFile = id.contains("#") ? id.substring(0, id.indexOf('#')) : id;
            String body = full.replaceAll("(?m)^\\*\\*适用范围\\*\\*[^\\r\\n]*(?:\\R|$)", "").strip();
            int allowance = Math.min(remaining, compact.isEmpty() ? EXCERPT_BYTES : 400);
            String excerpt = utf8Prefix(body, allowance);
            remaining -= excerpt.getBytes(StandardCharsets.UTF_8).length;
            compact.add(Map.of("sourceId", id, "title", Objects.toString(row.get("title"), ""),
                    "scope", KnowledgeProvenance.scope(sourceFile, full),
                    "excerpt", excerpt, "truncated", excerpt.length() < body.length()));
        }
        view.put("sources", compact);
        return view;
    }

    static String utf8Prefix(String value, int maximum) {
        int offset = 0, bytes = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            int width = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > maximum) break;
            bytes += width;
            offset += Character.charCount(codePoint);
        }
        return value.substring(0, offset);
    }
}
