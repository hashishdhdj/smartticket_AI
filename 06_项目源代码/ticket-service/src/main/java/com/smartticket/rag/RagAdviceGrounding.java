package com.smartticket.rag;

import java.util.List;

/** Narrow guard for invented workarounds; not a general semantic entailment verifier. */
public final class RagAdviceGrounding {
    private RagAdviceGrounding() {}

    private static final List<String> WORKAROUNDS = List.of("压缩", "拆分", "分卷", "分割", "分片上传", "绕过", "提高上限");

    public static boolean hasUnsupportedWorkaround(String advice, List<KnowledgeChunk> sources) {
        if (advice == null) return false;
        return WORKAROUNDS.stream().anyMatch(action -> recommends(advice, action)
                && sources.stream().noneMatch(source -> source.content().contains(action)));
    }

    private static boolean recommends(String advice, String action) {
        for (String clause : advice.split("[，,。；;！？\\n]|但是|不过|但|而是")) {
            int position=clause.indexOf(action);
            while(position>=0) {
                String prefix=clause.substring(Math.max(0,position-12),position);
                if (!prefix.matches(".*(?:不要|不得|不能|不可|不支持|禁止|不应|不建议|不提供|未支持|未实现).*")) return true;
                position=clause.indexOf(action,position+action.length());
            }
        }
        return false;
    }
}
