package com.smartticket.rag.impl;

import com.smartticket.governance.BudgetLedger;
import com.smartticket.security.ActorContext;

import com.smartticket.enums.ErrorCode;
import com.smartticket.rag.EmbeddingResult;
import com.smartticket.rag.EmbeddingService;
import com.smartticket.rag.KnowledgeVectorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link EmbeddingService} 实现：对 Spring AI {@link EmbeddingModel} 的一层薄封装。
 *
 * <p>与 {@code AiChatServiceImpl} 同构，多加了两件专属于"批量"的事：</p>
 * <ol>
 *   <li><b>分批</b>：服务方对单次请求的文本条数有硬上限（当前是 10），
 *       而知识库切片有几十条。分批交给本类做，调用方只关心"一批文本进、一批向量出"；</li>
 *   <li><b>逐条校验</b>：条数与维度都对不上时立刻失败，不让脏数据走到写库那一步。</li>
 * </ol>
 *
 * <p>不记录被向量化的文本内容：与 {@code TicketAnalysisServiceImpl} 同一取舍——
 * 生产日志不该承担留存用户文本的职责。</p>
 */
@Slf4j
@Service
public class EmbeddingServiceImpl implements EmbeddingService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.smartticket.governance.BudgetLedger budget;

    /** api-key 的占位值，与 application.yml 及 chat 侧保持一致；出现它即视为「未配置密钥」 */
    static final String PLACEHOLDER_API_KEY = "not-configured";

    private final EmbeddingModel embeddingModel;

    private final KnowledgeVectorProperties.Embedding embeddingProperties;

    /** 通道不可用的原因，写进日志与失败明细；可用时为 {@code null} */
    private final String unavailableReason;

    public EmbeddingServiceImpl(EmbeddingModel embeddingModel, KnowledgeVectorProperties properties) {
        this.embeddingModel = embeddingModel;
        this.embeddingProperties = properties.getEmbedding();

        this.unavailableReason = resolveUnavailableReason();
        if (unavailableReason == null) {
            log.info("向量化通道就绪 | protocol=openai-compatible | model={} | dimensions={} | batchSize={}",
                    embeddingProperties.getModel(), embeddingProperties.getDimensions(),
                    embeddingProperties.getBatchSize());
        } else {
            // 用 warn 而不是 error：未配置嵌入模型是本项目的合法状态（没有它 RAG 检索返回未命中，
            // 但工单与 AI 分类照常）。必须显眼，否则会变成"以为建了索引、其实一条都没写"的静默失效
            log.warn("向量化通道不可用，RAG 检索将返回未命中 | reason={}", unavailableReason);
        }
    }

    private String resolveUnavailableReason() {
        String apiKey = embeddingProperties.getApiKey();
        if (!StringUtils.hasText(apiKey) || PLACEHOLDER_API_KEY.equals(apiKey)) {
            return "未配置嵌入模型密钥（smartticket.vector.embedding.api-key 仍为占位值，"
                    + "可通过环境变量 EMBEDDING_API_KEY 注入）";
        }
        return null;
    }

    @Override
    public boolean isAvailable() {
        return unavailableReason == null;
    }

    @Override
    public String modelName() {
        return embeddingProperties.getModel();
    }

    @Override
    public EmbeddingResult embed(List<String> texts) {
        long start = System.currentTimeMillis();

        if (unavailableReason != null) {
            // debug 级别：通道不可用是启动时就已知的状态，不该在每次调用时刷 warn
            log.debug("向量化通道不可用，直接降级 | reason={}", unavailableReason);
            return EmbeddingResult.failed(ErrorCode.AI_NOT_CONFIGURED, unavailableReason, 0, modelName());
        }
        if (texts == null || texts.isEmpty()) {
            // 空输入不是错误：它只是"没有东西要向量化"，走成功路径让调用方少一个分支
            return EmbeddingResult.ok(List.of(), elapsedMs(start), modelName());
        }

        int batchSize = Math.max(1, embeddingProperties.getBatchSize());
        int expectedDimensions = embeddingProperties.getDimensions();
        List<float[]> collected = new ArrayList<>(texts.size());

        try {
            var actor=com.smartticket.security.ActorContext.current();
            if(actor!=null&&budget!=null)budget.reserve(actor.id(),texts.stream().mapToInt(text->text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum()+1024);
            for (int from = 0; from < texts.size(); from += batchSize) {
                int to = Math.min(from + batchSize, texts.size());
                List<String> batch = texts.subList(from, to);

                List<float[]> vectors = embeddingModel.embed(batch);

                if (vectors == null || vectors.size() != batch.size()) {
                    // 条数对不上说明"返回与输入不再一一对应"，此时无法判断哪个向量属于哪条文本，
                    // 拼装入库必然错位。宁可整批作废，也不能把错位的向量写进知识库
                    String detail = "向量条数与输入条数不一致：期望 " + batch.size()
                            + "，实际 " + (vectors == null ? "null" : vectors.size());
                    log.warn("向量化返回条数不符，已降级 | batch={}-{} | detail={}", from, to, detail);
                    return EmbeddingResult.failed(ErrorCode.AI_RESPONSE_UNPARSABLE, detail,
                            elapsedMs(start), modelName());
                }

                for (float[] vector : vectors) {
                    if (vector == null || vector.length != expectedDimensions) {
                        // 维度不符 = 配置与表结构脱钩，写库一定失败。在这里定性比让 PostgreSQL 报类型错误更早、更清楚
                        String detail = "向量维度与配置不符：期望 " + expectedDimensions
                                + "，实际 " + (vector == null ? "null" : vector.length);
                        log.warn("向量化返回维度不符，已降级 | detail={}", detail);
                        return EmbeddingResult.failed(ErrorCode.AI_RESPONSE_UNPARSABLE, detail,
                                elapsedMs(start), modelName());
                    }
                    collected.add(vector);
                }
            }

            long costMs = elapsedMs(start);
            log.info("向量化完成 | model={} | texts={} | batches={} | dimensions={} | cost={}ms",
                    modelName(), texts.size(), (texts.size() + batchSize - 1) / batchSize, expectedDimensions, costMs);
            return EmbeddingResult.ok(collected, costMs, modelName());

        } catch (Exception e) {
            // 与 AiChatServiceImpl 同样的宽泛捕获：这里是"可选能力的边界"，
            // 任何模型侧异常（连接超时、401、限流、配额用尽…）都应就地降级。
            long costMs = elapsedMs(start);
            log.warn("向量化调用失败，已降级 | model={} | texts={} | cost={}ms | cause={}: {}",
                    modelName(), texts.size(), costMs, e.getClass().getSimpleName(), e.getMessage());
            return EmbeddingResult.failed(ErrorCode.AI_CALL_FAILED,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), costMs, modelName());
        }
    }

    private long elapsedMs(long start) {
        return System.currentTimeMillis() - start;
    }

}
