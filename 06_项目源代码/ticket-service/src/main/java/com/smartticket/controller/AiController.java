package com.smartticket.controller;

import com.smartticket.common.Result;
import com.smartticket.dto.TicketAnalyzeDTO;
import com.smartticket.ai.TicketAnalysisService;
import com.smartticket.vo.TicketAnalysisVO;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 能力接口（阶段2 第3天）。
 *
 * <p>与 {@link TicketController} 同样的分层规矩：本层只做参数接收、调 Service、用
 * {@link Result} 包装，不写任何业务判断。</p>
 *
 * <p>路径用 {@code /api/ai/tickets} 而不是 {@code /api/tickets/ai}：AI 分析不以某个工单 id
 * 为主键，它是"对一段文本做分析"，与工单 CRUD 的资源层级并不相同；单独一段前缀也让
 * 将来"AI 能力不可用要不要整体摘掉"这类运维动作更好做。</p>
 *
 * <p><b>注意</b>：本接口在 AI 不可用时<b>不返回错误码</b>，而是返回 {@code code = 0}
 * 且 {@code data.degraded = true}。这不是遗漏——理由见 {@link TicketAnalysisVO} 的类注释。</p>
 */
@Validated
@RestController
@RequestMapping("/api/ai/tickets")
public class AiController {

    private final TicketAnalysisService ticketAnalysisService;

    public AiController(TicketAnalysisService ticketAnalysisService) {
        this.ticketAnalysisService = ticketAnalysisService;
    }

    /** 分析工单内容，返回分类与优先级推荐（AI 不可用时降级返回，不报错） */
    @PostMapping("/analyze")
    public Result<TicketAnalysisVO> analyze(@Valid @RequestBody TicketAnalyzeDTO dto) {
        return Result.success(ticketAnalysisService.analyze(dto));
    }

}
