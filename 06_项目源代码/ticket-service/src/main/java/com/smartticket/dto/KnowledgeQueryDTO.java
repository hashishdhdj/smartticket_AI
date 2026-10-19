package com.smartticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交前知识库检索的入参（阶段2 第5周接线，章程模块三「用户提交工单前，先进行知识库检索」）。
 *
 * <p>只有一个字段，且<b>不收工单 id</b>：这个接口的定位是"用户还没提单，先试着用知识库挡住它"，
 * 所以它在工单存在之前就要能调用——与 {@link TicketAnalyzeDTO} 同一考虑。</p>
 *
 * <h3>为什么长度上限是 500</h3>
 * 查询会被原样送去嵌入与关键词比对。过长不是"更能检索到"，而是<b>稀释</b>：
 * 嵌入向量会把多个意图平均掉，关键词相似度的分母也会变大。
 * 500 与切片上限同为 500——一段比单条切片还长的"问题"，本质上已经是一篇描述了，
 * 那种情况该走工单而不是走检索。
 */
@Data
public class KnowledgeQueryDTO {

    /** 用户的问题原文 */
    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题长度不能超过 500")
    private String query;

}
