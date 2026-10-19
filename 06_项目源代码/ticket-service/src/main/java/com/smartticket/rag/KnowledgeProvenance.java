package com.smartticket.rag;

import java.util.Set;

/** Current reviewed corpus provenance, including continuation chunks without a scope preamble. */
public final class KnowledgeProvenance {
    private static final Set<String> SIMULATED = Set.of(
            "faq/01-账户问题.md", "faq/02-支付问题.md", "faq/03-功能异常.md",
            "tickets/01-账户问题.md", "tickets/02-支付问题.md", "tickets/03-功能异常.md");
    private static final Set<String> PROJECT = Set.of(
            "faq/04-工单流程与通用问题.md", "faq/05-项目权限与AI处理.md");

    private KnowledgeProvenance() {}

    public static String scope(String sourceFile, String content) {
        if (SIMULATED.contains(sourceFile) || (content != null && content.contains("模拟客服平台场景")))
            return "SIMULATED_SCENARIO";
        return PROJECT.contains(sourceFile) ? "PROJECT_REFERENCE" : "UNSPECIFIED";
    }
}
