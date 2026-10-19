package com.smartticket.rag;

/** Retrieval-only synonyms; the original user question is still passed unchanged to the model. */
public final class KnowledgeQueryNormalizer {
    private KnowledgeQueryNormalizer() {}

    public static String normalize(String query) {
        return query == null ? null : query.replace("口令", "密码");
    }
}
