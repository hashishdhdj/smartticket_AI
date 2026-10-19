package com.smartticket.controller;

import com.smartticket.common.Result;
import com.smartticket.dto.KnowledgeQueryDTO;
import com.smartticket.rag.RagAnswerService;
import com.smartticket.vo.KnowledgeAnswerVO;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库问答接口（阶段2 第5周接线）。
 *
 * <p>这是章程模块三「用户提交工单前，先进行知识库检索」的落点：
 * 用户在提单页输入问题，先调本接口试一次——</p>
 *
 * <pre>
 * 有把握（needHuman=false）→ 展示推荐方案 + 相似问题 + 依据来源，用户可能自助解决
 * 没把握（needHuman=true）→ 引导用户继续提单，进入人工流程
 * </pre>
 *
 * <p>RAG 的检索与生成能力在第4、5周已经做完，但一直<b>没有对外的门</b>——
 * 本类就是那扇门。它不引入任何新算法：只做参数接收、调用
 * {@link RagAnswerService}、把结果映射成 {@link KnowledgeAnswerVO}。</p>
 *
 * <p>路径放在 {@code /api/ai/knowledge} 而不是塞进 {@code /api/ai/tickets}：
 * 与 {@code AiController} 的取舍一致——本接口不以某个工单 id 为主键，
 * 是"对一个文本问题做检索"，与工单的资源层级不同。</p>
 *
 * <p><b>注意</b>：本接口在检索降级时<b>不返回错误码</b>，而是返回 {@code code = 0}
 * 且 {@code data.needHuman = true}。理由见 {@link KnowledgeAnswerVO} 的类注释。</p>
 */
@Validated
@RestController
@RequestMapping("/api/ai/knowledge")
public class KnowledgeController {

    private final RagAnswerService ragAnswerService;

    public KnowledgeController(RagAnswerService ragAnswerService) {
        this.ragAnswerService = ragAnswerService;
    }

    /** 依据知识库回答问题（检索降级时降级返回，不报错） */
    @PostMapping("/answer")
    public Result<KnowledgeAnswerVO> answer(@Valid @RequestBody KnowledgeQueryDTO dto) {
        return Result.success(KnowledgeAnswerVO.of(ragAnswerService.answer(dto.getQuery())));
    }

}
