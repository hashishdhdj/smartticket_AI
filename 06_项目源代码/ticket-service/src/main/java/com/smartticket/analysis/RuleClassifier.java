package com.smartticket.analysis;

import com.smartticket.vo.TicketAnalysisVO;

import java.util.List;

/** Explicit fallback suggestions; never overwrites the human-owned fields. */
public final class RuleClassifier {
    private RuleClassifier() {}

    public static TicketAnalysisVO fallback(String text, TicketAnalysisVO failure) {
        String category =
                text.matches("(?s).*(支付|扣款|退款|订单).*")
                        ? "支付问题"
                        : text.matches("(?s).*(登录|密码|验证码|账号).*")
                                ? "账户问题"
                                : text.matches("(?s).*(页面|报错|功能).*") ? "功能异常" : "其他";
        return new TicketAnalysisVO(
                true,
                failure.degradedReasonCode(),
                failure.degradedReason(),
                category,
                "MEDIUM",
                null,
                List.of(),
                "关键词规则兜底建议，需人工复核；未使用模型分析",
                "rule-classify-v1",
                "rule-engine",
                failure.costMs());
    }
}
