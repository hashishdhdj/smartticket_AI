package com.smartticket.agent;

import com.smartticket.rag.KnowledgeProvenance;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded model context; the API/audit keeps the original tool evidence. */
public final class AgentToolContext {
    static final int EXCERPT_BYTES = 1400;

    private AgentToolContext() {}

    public static boolean knowledgeTool(String tool) {
        return "searchKnowledge".equals(tool) || "searchExperienceGraph".equals(tool);
    }

    public static Object compact(String tool, Object result, Set<String> providedSources) {
        return compact(tool,result,providedSources,new HashSet<>());
    }

    public static Object compact(String tool,Object result,Set<String> providedSources,Set<String> providedGraphs) {
        if ("searchExperienceGraph".equals(tool)) return graph(result,providedSources,providedGraphs);
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

    private static Object graph(Object result,Set<String> providedSources,Set<String> providedGraphs) {
        if (!(result instanceof Map<?,?> outer) || !(outer.get("sources") instanceof List<?> sources)) return result;
        Map<String,Object> view=new LinkedHashMap<>();
        for (String key:List.of("requestedQuery","effectiveQuery","queryOrigin","degraded","maxHops","reason","fallback"))
            if (outer.containsKey(key)) view.put(key,outer.get(key));
        List<Object> rows=new ArrayList<>(); int remaining=6500, omitted=0;
        for (Object value:sources) {
            if (!(value instanceof Map<?,?> row)) continue;
            String id=Objects.toString(row.get("sourceId"),"");
            if (id.isBlank()) continue;
            if (providedGraphs.contains(id)) { rows.add(Map.of("sourceId",id,"alreadyProvided",true)); continue; }
            Map<String,Object> compact=new LinkedHashMap<>();
            for (String key:List.of("sourceId","title","scope","factScope","revision","graphHash","facts","contextPaths","references","notice"))
                if (row.containsKey(key)) compact.put(key,row.get(key));
            compact.put("truncated",false);
            int bytes;
            try { bytes=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(compact).length; }
            catch (Exception invalid) { throw new IllegalStateException("Invalid graph context",invalid); }
            // Atomic per-source admission: a shortened condition or lost negation is unsafe evidence.
            if (bytes>remaining) { omitted++; continue; }
            rows.add(compact); remaining-=bytes; providedSources.add(id); providedGraphs.add(id);
        }
        view.put("sources",rows); view.put("omittedSources",omitted);
        if (omitted>0) view.put("contextLimitNotice","整份经验超过上下文额度，已省略且不可引用；可缩小检索或转人工。");
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
