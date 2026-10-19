package com.smartticket.rag.impl;

import com.smartticket.ai.AiCallResult;
import com.smartticket.ai.AiChatService;
import com.smartticket.ai.AiResponseInvalidException;
import com.smartticket.enums.ErrorCode;
import com.smartticket.rag.FusedHit;
import com.smartticket.rag.KnowledgeChunk;
import com.smartticket.rag.KnowledgeProvenance;
import com.smartticket.rag.KnowledgeSearchResult;
import com.smartticket.rag.KnowledgeSearchService;
import com.smartticket.rag.KnowledgeVectorProperties;
import com.smartticket.rag.RagAnswer;
import com.smartticket.rag.RagAdviceGrounding;
import com.smartticket.rag.RagAnswerParser;
import com.smartticket.rag.RagAnswerPayload;
import com.smartticket.rag.RagAnswerPromptFactory;
import com.smartticket.rag.RagAnswerService;
import com.smartticket.rag.RagSource;
import com.smartticket.rag.RetrievalResult;
import com.smartticket.rag.RetrievalSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RagAnswerService} 实现（阶段2 第5周任务 3/4/5/6）。
 *
 * <h3>置信度取"向量路最高相似度"，不是融合分</h3>
 * 这不是偏好而是**评估数据逼出来的**（见 {@code 07_测试与质量/04_AI评估集/低置信案例集.md}）：
 *
 * <pre>
 *              负样本（该拒答）      正样本（该作答）
 * 融合分       全部 0.01639          12 条也是 0.01639   ← 完全重叠，零区分度
 * 向量路 top1  0.2726 ~ 0.6412       0.6845 ~ 0.9025     ← 有明确空档
 * </pre>
 *
 * 融合分恒为 {@code 1/61} 的原因见 {@code RrfFusion}：它是"排第几"的倒数，
 * 而**任何查询都有第 1 名**，所以它天生无法表达"这条到底相不相关"。
 *
 * <h3>向量路挂了会怎样</h3>
 * 置信度取不到 → 记 0 → 必然低于阈值 → {@code needHuman=true}，且**不会调用模型**。
 * 这是想要的：**嵌入服务不可用时宁可转人工，也不能凭关键词路的低分结果硬答**。
 * （关键词路的分数同样不可用作置信度：实测负样本 N02 得了 0.25，而正样本 Q13 只有 0.20，是反的。）
 *
 * <h3>只在"有把握"时才调模型生成建议与相似问题</h3>
 * {@code needHuman=true} 时**根本不调模型**，而不是"生成了但不返回"。理由有二：
 * <ol>
 *   <li>省一次调用——低置信度的结果是注定不用的；</li>
 *   <li>从根上杜绝"让模型去解释它没把握的内容"——那是幻觉最容易发生的场合。</li>
 * </ol>
 * 模型调用失败或返回内容不合规时，建议为空并明确 {@code needHuman=true}。
 * 此时仍保留真实来源供人工使用。相似问题由真实检索小节标题确定，不采用模型发散输出。
 */
@Slf4j
@Service
public class RagAnswerServiceImpl implements RagAnswerService {

    /** 来源摘录长度。够人一眼看清这节讲什么，又不至于把返回体撑大 */
    private static final int SUMMARY_LENGTH = 150;

    private final KnowledgeSearchService searchService;

    private final RagAnswerPromptFactory promptFactory;

    private final AiChatService aiChatService;

    private final RagAnswerParser answerParser;

    private final double confidenceThreshold;

    public RagAnswerServiceImpl(KnowledgeSearchService searchService,
                                RagAnswerPromptFactory promptFactory,
                                AiChatService aiChatService,
                                RagAnswerParser answerParser,
                                KnowledgeVectorProperties properties) {
        this.searchService = searchService;
        this.promptFactory = promptFactory;
        this.aiChatService = aiChatService;
        this.answerParser = answerParser;
        this.confidenceThreshold = properties.getConfidenceThreshold();
    }

    @Override
    public RagAnswer answer(String query) {
        long start = System.currentTimeMillis();

        KnowledgeSearchResult search = searchService.search(query);

        double confidence = confidenceOf(search);
        Map<String, Double> vectorScores = vectorScoresOf(search);
        List<RagSource> sources = search.hits().stream()
                .map(hit -> toSource(hit, vectorScores))
                .toList();

        boolean matched = !sources.isEmpty();
        boolean needHuman = confidence < confidenceThreshold;

        // 只有"有把握"才生成建议与相似问题。低置信度时不调模型，见类注释
        Generation generated = needHuman ? new Generation(null,
                search.degradedSources().contains(RetrievalSource.VECTOR) ? "VECTOR_UNAVAILABLE" : "BELOW_THRESHOLD")
                : generateAnswer(query, search.hits());
        RagAnswerPayload payload = generated.payload();
        String statusReason = generated.reason();
        // 相似度高只说明主题接近；模型可以追加拒答，不能解除低置信度门禁。
        if (payload != null && Boolean.TRUE.equals(payload.needHuman())) {
            payload = null;
            statusReason = "MODEL_REFUSED";
        }
        String suggestion = payload == null ? null : payload.suggestion();
        if (RagAdviceGrounding.hasUnsupportedWorkaround(suggestion, search.hits().stream().map(FusedHit::chunk).toList())) {
            payload = null;
            suggestion = null;
            statusReason = "UNSUPPORTED_WORKAROUND";
        }
        // The model has identified a demo answer; make the scope boundary explicit in code.
        // Do not apply this to project-rule answers merely because a noisy demo hit was retrieved.
        if (suggestion != null && suggestion.startsWith("演示规则：")
                && search.hits().stream().anyMatch(hit -> java.util.Set.of("SIMULATED_SCENARIO","REVIEWED_TICKET_DEMO").contains(
                        KnowledgeProvenance.scope(hit.chunk().sourceFile(), hit.chunk().content())))) {
            String scoped = "演示规则（非本项目实际能力）：" + suggestion.substring("演示规则：".length());
            if (scoped.length() > 200) {
                payload = null;
                suggestion = null; // Never cut off a condition or an important limitation.
                statusReason = "OUTPUT_TOO_LONG";
            } else suggestion = scoped;
        }
        List<String> similarQuestions = payload == null ? List.of() : search.hits().stream()
                .map(hit->hit.chunk().sectionTitle()).distinct().limit(4).toList();
        needHuman = needHuman || payload == null;

        long costMs = System.currentTimeMillis() - start;

        if (needHuman) {
            // 转人工是"可选能力的边界"，不打 error：它不阻塞任何主流程。
            // 但必须记下足够定位的信息——尤其是 degradedSources，它区分"库里没有"与"那一路挂了"
            log.info("RAG 转人工（检索不足或内容生成失败） | queryChars={} | confidence={} | threshold={} | statusReason={} "
                            + "| matched={} | sources={} | degradedSources={} | cost={}ms",
                    lengthOf(query), confidence, confidenceThreshold, statusReason, matched, sources.size(),
                    search.degradedSources(), costMs);
        } else {
            log.info("RAG 可作答 | queryChars={} | confidence={} | sources={} | suggestion={} "
                            + "| similarQuestions={} | topSource={} | cost={}ms",
                    lengthOf(query), confidence, sources.size(),
                    suggestion == null ? "生成失败" : "已生成",
                    similarQuestions.size(),
                    sources.isEmpty() ? "-" : sources.get(0).sourceRef(), costMs);
        }

        return new RagAnswer(matched, confidence, sources, suggestion, similarQuestions,
                needHuman, search.degradedSources(), costMs, statusReason);
    }

    /**
     * 依据检索到的切片生成建议与相似问题（同一次模型调用）。
     *
     * @return 生成失败或内容不合规时返回 {@code null}，调用方仍可用 {@code sources} 作答
     */
    private record Generation(RagAnswerPayload payload, String reason) {}

    private Generation generateAnswer(String query, List<FusedHit> hits) {
        if (hits.isEmpty()) {
            return new Generation(null, "NO_SOURCES");
        }
        // 用切片的完整正文（不是 RagSource 里给人看的截断摘录）：模型需要原文才能作答
        List<KnowledgeChunk> grounding = hits.stream().map(FusedHit::chunk).toList();
        AiCallResult result = aiChatService.chat(
                promptFactory.systemPrompt(),
                promptFactory.userPrompt(query, grounding));

        if (!result.success()) {
            log.warn("RAG 内容生成失败，本次只返回来源 | queryChars={} | promptVersion={} "
                            + "| reasonCode={} | cost={}ms | detail={}",
                    lengthOf(query), RagAnswerPromptFactory.PROMPT_VERSION,
                    result.errorCode().getCode(), result.costMs(), result.failureDetail());
            String detail = String.valueOf(result.failureDetail()).toLowerCase(java.util.Locale.ROOT);
            String reason = result.errorCode() == ErrorCode.AI_NOT_CONFIGURED ? "AI_UNAVAILABLE"
                    : result.errorCode() == ErrorCode.AI_BUDGET_EXHAUSTED ? "BUDGET_EXHAUSTED"
                    : detail.contains("timeout") || detail.contains("timed out") ? "MODEL_TIMEOUT"
                    : detail.contains("eof") || detail.contains("ssl") || detail.contains("connect")
                            || detail.contains("upstream_transport") ? "MODEL_TRANSPORT_FAILURE" : "MODEL_CALL_FAILED";
            return new Generation(null, reason);
        }

        try {
            return new Generation(answerParser.parse(result.content()), "ANSWERED");
        } catch (AiResponseInvalidException e) {
            // 模型答了但不守规矩。与"调用失败"分开记原因码，两者的排查方向不同：
            // 解析失败占比高该改 Prompt，调用失败占比高该查网络与厂商
            log.warn("RAG 内容生成不合规，本次只返回来源 | queryChars={} | promptVersion={} "
                            + "| reasonCode={} | cost={}ms | detail={}",
                    lengthOf(query), RagAnswerPromptFactory.PROMPT_VERSION,
                    ErrorCode.AI_RESPONSE_UNPARSABLE.getCode(), result.costMs(), e.getMessage());
            return new Generation(null, "INVALID_MODEL_OUTPUT");
        }
    }

    /**
     * 置信度 = 向量路最高相似度；该路降级或没有命中时为 0。
     *
     * <p>只看向量路最高那一条，而不是"命中的平均分"：平均分会被后几名稀释——
     * 一条精确命中的答案配四条噪声，平均下来反而显得没把握，与事实相反。</p>
     */
    private static double confidenceOf(KnowledgeSearchResult search) {
        return search.pathResults().stream()
                .filter(path -> path.source() == RetrievalSource.VECTOR)
                .filter(RetrievalResult::success)
                .map(path -> path.hits().isEmpty() ? 0.0 : path.hits().get(0).score())
                .findFirst()
                .orElse(0.0);
    }

    /** 切片的唯一键 → 该切片在向量路的相似度。键与融合层一致（文件 + 切片序号） */
    private static Map<String, Double> vectorScoresOf(KnowledgeSearchResult search) {
        Map<String, Double> scores = new HashMap<>();
        search.pathResults().stream()
                .filter(path -> path.source() == RetrievalSource.VECTOR && path.success())
                .flatMap(path -> path.hits().stream())
                .forEach(hit -> scores.put(keyOf(hit.chunk()), hit.score()));
        return scores;
    }

    private static RagSource toSource(FusedHit hit, Map<String, Double> vectorScores) {
        KnowledgeChunk chunk = hit.chunk();
        return new RagSource(
                chunk.sourceFile(),
                chunk.sectionTitle(),
                vectorScores.getOrDefault(keyOf(chunk), 0.0),
                hit.sources(),
                excerpt(chunk.content()));
    }

    private static String keyOf(KnowledgeChunk chunk) {
        return chunk.sourceFile() + '#' + chunk.chunkIndex();
    }

    /**
     * 正文摘录：折叠空白成一行后截断。
     *
     * <p>折叠空白不是美化——切片正文本身是多行 Markdown（含 {@code **适用场景**} 等标记），
     * 不折叠就会在返回体里塞进换行与缩进。</p>
     */
    private static String excerpt(String content) {
        if (content == null) {
            return null;
        }
        String flattened = content.replaceAll("\\s+", " ").strip();
        return flattened.length() <= SUMMARY_LENGTH
                ? flattened
                : flattened.substring(0, SUMMARY_LENGTH) + "…";
    }

    private static int lengthOf(String query) {
        return query == null ? 0 : query.length();
    }

}
