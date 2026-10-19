package com.smartticket.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** A versioned, bounded source key; never a path read from the filesystem. */
public record KnowledgeSource(String candidateId, long revision) {
    private static final Pattern SOURCE = Pattern.compile("^resolved-ticket/([0-9a-f-]{36})/v([1-9][0-9]*)\\.md$");

    public KnowledgeSource {
        if (!UUID.fromString(candidateId).toString().equals(candidateId) || revision <= 0)
            throw new IllegalArgumentException("Invalid knowledge source");
    }

    public String prefix() { return "resolved-ticket/" + candidateId + "/"; }
    public String file() { return prefix() + "v" + revision + ".md"; }

    public static KnowledgeSource parse(String file) {
        var match = SOURCE.matcher(file);
        if (!match.matches()) throw new IllegalArgumentException("Invalid dynamic knowledge source");
        return new KnowledgeSource(match.group(1), Long.parseLong(match.group(2)));
    }

    public static String content(String question, String answer) {
        return "适用范围：已审核的合成工单经验，仅限演示；不代表通用产品规则。\n问题："
                + question + "\n解决方案：" + answer;
    }

    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
