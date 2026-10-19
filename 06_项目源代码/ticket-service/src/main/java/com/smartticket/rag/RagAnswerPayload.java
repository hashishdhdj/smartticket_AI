package com.smartticket.rag;

import java.util.List;

/**
 * 模型返回的 RAG 回答内容（{@code rag-answer-v3} 的结构化输出）。
 *
 * <p>三个字段：{@link #suggestion()}（一条可执行建议）与
 * {@link #similarQuestions()}（知识库里还有的相似问题）与 {@link #needHuman()}（资料是否不足）。</p>
 *
 * <h3>为什么这个 record 允许 null</h3>
 * 它是 Jackson 反序列化的落地类型：模型少给一个字段，对应分量就是 null。
 * <b>字段非 null 由 {@link RagAnswerParser} 保证，而不是由 record 保证</b>——
 * 这与 {@code AiClassificationResult} 的约定一致：把"结构是否完整"的判定集中在一个地方，
 * 才能做到"要么全部通过、要么抛异常"，不存在"部分合法就先用着"的中间态。
 */
public record RagAnswerPayload(String suggestion, List<String> similarQuestions, Boolean needHuman) {

}
