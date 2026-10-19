package com.smartticket.rag;

import com.smartticket.ai.AiChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RAG 回答链路的真实厂商验证（阶段2 第5周任务 6）。
 *
 * <p>Prompt 变更有固定流程（见 {@code 02_AI约束与规范/03_Prompt模板/README.md}），
 * 其中"跑真实模型验证"这一步不可省：单元测试用的是我们自己造的输出，
 * 只能证明"代码能处理合规输入"，**证明不了模型会产出合规输出**。</p>
 *
 * <p>本测试要回答三个只有真实调用能回答的问题：</p>
 * <ol>
 *   <li>模型会不会按模板要求只回**一句、无前缀、无 Markdown** 的中文建议；</li>
 *   <li>会不会违反"不得提及资料/知识库"的要求；</li>
 *   <li>低置信度的问题，链路是否**根本没调模型**（suggestion 为 null）。</li>
 * </ol>
 *
 * <p>需真实嵌入 + 真实对话两次调用，默认跳过。</p>
 *
 * <pre>
 * PowerShell:  $env:AI_SMOKE = "true"; mvn test -Dtest=RagAnswerRealVendorTest
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AI_SMOKE", matches = "true",
        disabledReason = "默认跳过：本测试会真实调用嵌入与对话接口并产生费用，需显式设置 AI_SMOKE=true 开启")
class RagAnswerRealVendorTest {

    @Autowired
    private RagAnswerService ragAnswerService;

    @Autowired
    private AiChatService aiChatService;

    @Test
    @DisplayName("高置信度问题：真实生成一句合规的建议，且来源齐备")
    void confidentQuestionProducesCompliantSuggestion() {
        assumeTrue(aiChatService.isAvailable(),
                "AI 通道不可用（未配密钥或总开关关闭），跳过真实调用 —— 看启动日志里的 reason");

        RagAnswer answer = ragAnswerService.answer("重复扣款如何申请退款");

        System.out.println();
        System.out.println("==================== 真实 RAG 回答 ====================");
        System.out.println("[问题] 重复扣款如何申请退款");
        System.out.println("[置信度] " + answer.confidence() + " | needHuman=" + answer.needHuman()
                + " | matched=" + answer.matched());
        System.out.println("[来源]");
        answer.sources().forEach(source -> System.out.println("  - " + source.sourceRef()
                + " | score=" + source.score() + " | via=" + source.via()));
        System.out.println("[建议] " + answer.suggestion());
        System.out.println("[相似问题] " + answer.similarQuestions());
        System.out.println("=====================================================");

        assertFalse(answer.needHuman(), "该问题在评估集中向量路 top1 = 0.8130，远高于阈值 0.66");
        assertTrue(answer.matched());
        assertFalse(answer.sources().isEmpty(), "RAG 结果必须包含来源（阶段2 约束第 7 条）");

        String suggestion = answer.suggestion();
        assertNotNull(suggestion, "高置信度时应生成建议");
        assertFalse(suggestion.isBlank());
        assertFalse(suggestion.contains("\n"), "拆掉 JSON 后建议应为单行");
        assertFalse(suggestion.contains("```"), "解析器会剥掉代码块围栏，内容里不该再有");
        assertFalse(suggestion.contains("根据资料") || suggestion.contains("知识库"),
                "Prompt 要求不要提及'资料/知识库'字样，直接把做法说出来");

        assertFalse(answer.similarQuestions().isEmpty(), "章程要求返回相似问题");
        answer.similarQuestions().forEach(question -> {
            assertFalse(question.isBlank());
            assertTrue(question.length() <= 40, "相似问题超长会被解析器拒掉：" + question);
        });
    }

    @Test
    @DisplayName("低置信度问题：走转人工，建议为 null（链路根本没调模型）")
    void lowConfidenceQuestionGoesToHumanWithoutSuggestion() {
        assumeTrue(aiChatService.isAvailable(),
                "AI 通道不可用（未配密钥或总开关关闭），跳过真实调用 —— 看启动日志里的 reason");

        // 「如何修改发票抬头」是评估集的负样本（语料只有「如何申请开具发票」），
        // 实测向量路 top1 = 0.5414，低于阈值 0.66
        RagAnswer answer = ragAnswerService.answer("如何修改发票抬头");

        System.out.println();
        System.out.println("[问题] 如何修改发票抬头");
        System.out.println("[置信度] " + answer.confidence() + " | needHuman=" + answer.needHuman()
                + " | matched=" + answer.matched() + " | suggestion=" + answer.suggestion());

        assertTrue(answer.needHuman(), "0.5414 < 0.66，必须转人工");
        assertNull(answer.suggestion(), "转人工时不得给出 AI 自拟的建议（防幻觉）");
    }

}
