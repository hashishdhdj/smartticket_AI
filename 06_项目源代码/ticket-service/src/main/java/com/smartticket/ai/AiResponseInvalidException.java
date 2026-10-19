package com.smartticket.ai;

/**
 * AI 返回内容不符合约定结构时抛出。
 *
 * <p><b>这是一条内部信号，不是对外错误。</b>它不携带 {@code ErrorCode}，
 * 由调用方（第3天的分类服务）决定翻译成哪个对外错误码——让 AI 层预埋 HTTP 语义
 * 会把"模型返回不合规"这种内部事实过早绑定到接口契约上。</p>
 *
 * <p>用非受检异常：解析失败需要一路穿过解析器与将来的分类服务回到服务边界，
 * 而中间层对它无能为力。边界处统一 catch 一次，比每层都声明它更清楚。</p>
 *
 * <p>message 里会带上具体违规点与实际取值（已截断），这样第5周跑评估集时
 * 能直接从日志统计"模型最常违反哪条约束"，而不必复现请求。</p>
 */
public class AiResponseInvalidException extends RuntimeException {

    public AiResponseInvalidException(String message) {
        super(message);
    }

}
