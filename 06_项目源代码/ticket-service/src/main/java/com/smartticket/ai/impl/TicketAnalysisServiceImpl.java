package com.smartticket.ai.impl;

import com.smartticket.ai.AiCallResult;
import com.smartticket.ai.AiChatService;
import com.smartticket.ai.AiClassificationParser;
import com.smartticket.ai.AiClassificationResult;
import com.smartticket.ai.AiPromptFactory;
import com.smartticket.ai.AiResponseInvalidException;
import com.smartticket.ai.TicketAnalysisService;
import com.smartticket.dto.TicketAnalyzeDTO;
import com.smartticket.enums.ErrorCode;
import com.smartticket.vo.TicketAnalysisVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link TicketAnalysisService} 实现：把 Prompt 渲染、模型调用、结果解析串成一次分析。
 *
 * <h3>为什么这里能"永不抛异常"</h3>
 * 只需要在<b>一处</b>兜住两类失败：{@link AiChatService} 已经把模型侧异常转成了失败结果，
 * 本类再把 {@link AiResponseInvalidException} 转成降级结果。因为每个下游都遵循
 * "返回结果而不是抛异常"的约定，这个边界才收得住——如果解析器改成抛受检异常往上穿透，
 * 这里就得层层声明，最终总有某个调用点会漏掉 try。
 *
 * <h3>AI 调用日志（章程第3周任务 8）</h3>
 * 每次分析都留一条结构化日志，字段固定为 {@code result / reasonCode / category / priority /
 * confidence / model / costMs / promptVersion}，便于第5周直接从日志统计：降级率、
 * 降级原因分布（未配置 / 调用失败 / 格式不符）、耗时趋势、分类分布。
 *
 * <p><b>刻意不记工单原文与模型输出的关键词、理由</b>：那是用户输入及其衍生内容，
 * 生产日志不该承担留存用户文本的职责。第5周评估集需要"输入 + 输出原文"，
 * 届时走受控的评估采集流程，不从生产日志里捞。</p>
 */
@Slf4j
@Service
public class TicketAnalysisServiceImpl implements TicketAnalysisService {

    private final AiChatService aiChatService;

    private final AiPromptFactory aiPromptFactory;

    private final AiClassificationParser aiClassificationParser;

    public TicketAnalysisServiceImpl(AiChatService aiChatService,
                                     AiPromptFactory aiPromptFactory,
                                     AiClassificationParser aiClassificationParser) {
        this.aiChatService = aiChatService;
        this.aiPromptFactory = aiPromptFactory;
        this.aiClassificationParser = aiClassificationParser;
    }

    @Override
    public TicketAnalysisVO analyze(TicketAnalyzeDTO dto) {
        String systemPrompt = aiPromptFactory.classificationSystemPrompt();
        String userPrompt = aiPromptFactory.classificationUserPrompt(dto.getTitle(), dto.getDescription());

        AiCallResult call = aiChatService.chat(systemPrompt, userPrompt);

        if (!call.success()) {
            // 通道未配置 / 模型超时 / 鉴权失败等。detail 只写日志不返回前端（可能含内部信息）
            log.warn("AI 工单分析降级 | result=degraded | reasonCode={} | detail={} | model={} | costMs={} | promptVersion={}",
                    call.errorCode().getCode(), call.failureDetail(), call.model(),
                    call.costMs(), AiPromptFactory.PROMPT_VERSION);
            return TicketAnalysisVO.degraded(call.errorCode(), call.model(),
                    AiPromptFactory.PROMPT_VERSION, call.costMs());
        }

        try {
            AiClassificationResult parsed = aiClassificationParser.parse(call.content());
            log.info("AI 工单分析完成 | result=ok | category={} | priority={} | confidence={} | model={} | costMs={} | promptVersion={}",
                    parsed.category(), parsed.priority(), parsed.confidence(), call.model(),
                    call.costMs(), AiPromptFactory.PROMPT_VERSION);
            return TicketAnalysisVO.analyzed(parsed, AiPromptFactory.PROMPT_VERSION,
                    call.model(), call.costMs());

        } catch (AiResponseInvalidException e) {
            // 模型答了但不守规矩。与"调用失败"分开记原因码，两者的排查方向不同
            log.warn("AI 工单分析降级 | result=degraded | reasonCode={} | detail={} | model={} | costMs={} | promptVersion={}",
                    ErrorCode.AI_RESPONSE_UNPARSABLE.getCode(), e.getMessage(), call.model(),
                    call.costMs(), AiPromptFactory.PROMPT_VERSION);
            return TicketAnalysisVO.degraded(ErrorCode.AI_RESPONSE_UNPARSABLE, call.model(),
                    AiPromptFactory.PROMPT_VERSION, call.costMs());
        }
    }

}
