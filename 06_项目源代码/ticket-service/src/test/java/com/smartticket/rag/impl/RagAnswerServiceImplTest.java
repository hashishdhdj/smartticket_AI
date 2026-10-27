package com.smartticket.rag.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartticket.ai.AiCallResult;
import com.smartticket.ai.AiChatService;
import com.smartticket.enums.ErrorCode;
import com.smartticket.rag.FusedHit;
import com.smartticket.rag.KnowledgeChunk;
import com.smartticket.rag.KnowledgeSearchResult;
import com.smartticket.rag.KnowledgeSearchService;
import com.smartticket.rag.KnowledgeVectorProperties;
import com.smartticket.rag.RagAnswer;
import com.smartticket.rag.RagAnswerParser;
import com.smartticket.rag.RagAnswerPromptFactory;
import com.smartticket.rag.RagAnswerService;
import com.smartticket.rag.RagSource;
import com.smartticket.rag.RetrievalHit;
import com.smartticket.rag.RetrievalResult;
import com.smartticket.rag.RetrievalSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import com.smartticket.vo.KnowledgeAnswerVO;

/**
 * {@link RagAnswerServiceImpl} 单元测试（阶段2 第5周任务 3/4/5/6）。
 *
 * <p>纯单测：检索与对话通道都换成 mock，不连库、不调接口、零成本。
 * Prompt 工厂与解析器用<b>真实实例</b>（模板是随包资源，读得到），
 * 这样"模板缺失""模型返回结构变了"这类问题也会在这里暴露。</p>
 *
 * <p>真实模型效果由 {@code RagAnswerRealVendorTest}（需 AI_SMOKE）验证。</p>
 */
class RagAnswerServiceImplTest {

    private static final double THRESHOLD = new KnowledgeVectorProperties().getConfidenceThreshold();

    /** 仅用于在测试里拼出模型返回的 JSON，避免手写转义 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);

    private final AiChatService aiChatService = mock(AiChatService.class);

    private final RagAnswerService ragAnswerService = new RagAnswerServiceImpl(
            searchService, new RagAnswerPromptFactory(), aiChatService, new RagAnswerParser(), properties());

    private static KnowledgeVectorProperties properties() {
        KnowledgeVectorProperties properties = new KnowledgeVectorProperties();
        properties.setConfidenceThreshold(THRESHOLD);
        return properties;
    }

    private void givenSearch(KnowledgeSearchResult result) {
        when(searchService.search(any())).thenReturn(result);
    }

    /** 让模型返回一份合规的 JSON 回答 */
    private void givenModelReturns(String suggestion, String... similarQuestions) {
        String json;
        try {
            json = MAPPER.writeValueAsString(
                    Map.of("suggestion", suggestion, "similarQuestions", List.of(similarQuestions), "needHuman", false));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.ok(json, 500, "stub-model"));
    }

    /** 让模型返回一段任意原始文本（用于构造"不合规"的响应） */
    private void givenModelReturnsRaw(String content) {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.ok(content, 500, "stub-model"));
    }

    // ------------------------------------------------------------ 高置信度：作答并生成内容

    @ParameterizedTest
    @ValueSource(doubles = {0.91, 0.5989091154011912})
    void admittedSimilarityDoesNotOverrideModelSemanticAbstention(double score) {
        KnowledgeChunk target = chunk("faq/02-支付问题.md", "如何查询退款", 0,
                "退款进度需查具体订单的处理记录，知识文档不包含真实订单数据。");
        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(target, 0.03, EnumSet.of(RetrievalSource.VECTOR))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR,
                        List.of(new RetrievalHit(target, score)), 2)), 2));
        givenModelReturnsRaw(decision(true,"INSUFFICIENT_EVIDENCE","CRITICAL_FACTS_MISSING","缺少这笔订单的真实退款处理记录"));
        var answer = ragAnswerService.answer("我的这笔退款到底到账没有？");
        assertTrue(answer.matched());
        assertTrue(answer.needHuman());
        assertNull(answer.suggestion());
        assertEquals(score, answer.confidence());
        assertEquals("CRITICAL_FACTS_MISSING", answer.statusReason());
        assertEquals("缺少这笔订单的真实退款处理记录", KnowledgeAnswerVO.of(answer).handoffDetail());
        verify(aiChatService).chat(anyString(), anyString());
        assertFalse(answer.sources().isEmpty());
        assertTrue(answer.similarQuestions().isEmpty());
    }

    private String decision(boolean human,String state,String reason,String detail) {
        try {
            return MAPPER.writeValueAsString(Map.of("suggestion","请依据当前资料处理；缺少信息时请补充。",
                    "similarQuestions",List.of("工单处理规则"),"needHuman",human,
                    "handoff",Map.of("state",state,"reason",reason,"detail",detail)));
        } catch(JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private void explanationSource() {
        givenSearch(highConfidenceSearch(chunk("faq/project.md","PUT更新规则",0,"PUT为全量更新，未传可空描述可能被清空。")));
    }

    @Test void answeredExplanationCannotKeepHumanFlagAndGetsOneRepair() {
        explanationSource();
        when(aiChatService.chat(anyString(),anyString())).thenReturn(
                AiCallResult.ok(decision(true,"ANSWERED","NONE",""),1,"fixture"),
                AiCallResult.ok(decision(false,"ANSWERED","NONE",""),1,"fixture"));
        var vo=KnowledgeAnswerVO.of(ragAnswerService.answer("PUT未传描述是否保留？"));
        assertFalse(vo.needHuman());assertEquals("ANSWER",vo.nextAction());assertEquals("NONE",vo.handoffReason());
        var prompts=ArgumentCaptor.forClass(String.class);verify(aiChatService,times(2)).chat(prompts.capture(),anyString());
        assertTrue(prompts.getAllValues().get(1).contains("上一条人工升级判定"));
    }
    @Test void repeatedUnjustifiedHandoffAsksForDetailsWithoutEscalating() {
        explanationSource();givenModelReturnsRaw("{\"suggestion\":\"资料说明了PUT规则，请转人工\",\"similarQuestions\":[\"PUT规则\"],\"needHuman\":true}");
        var vo=KnowledgeAnswerVO.of(ragAnswerService.answer("PUT规则是什么？"));
        assertFalse(vo.needHuman());assertEquals("ASK_USER",vo.nextAction());assertEquals("CLARIFICATION_REQUIRED",vo.statusReason());
        assertFalse(vo.sources().isEmpty());assertNotNull(vo.suggestion());verify(aiChatService,times(2)).chat(anyString(),anyString());
    }
    @Test void missingUserContextIsAQuestionRatherThanAHandoff() {
        explanationSource();givenModelReturnsRaw(decision(false,"NEEDS_CLARIFICATION","USER_CONTEXT_REQUIRED","请说明你需要修改哪张工单的哪个字段"));
        var vo=KnowledgeAnswerVO.of(ragAnswerService.answer("帮我改一下"));
        assertFalse(vo.needHuman());assertEquals("ASK_USER",vo.nextAction());assertEquals("CLARIFICATION_REQUIRED",vo.statusReason());
        verify(aiChatService).chat(anyString(),anyString());
    }
    @Test void actualManualOperationRetainsItsConcreteBlocker() {
        explanationSource();givenModelReturnsRaw(decision(true,"HUMAN_ACTION_REQUIRED","HUMAN_OPERATION_REQUIRED","用户要求立即办理人工账号申诉核验"));
        var vo=KnowledgeAnswerVO.of(ragAnswerService.answer("请现在替我办理人工申诉核验"));
        assertTrue(vo.needHuman());assertEquals("HUMAN_SUPPORT",vo.nextAction());assertEquals("HUMAN_OPERATION_REQUIRED",vo.handoffReason());
        assertEquals("用户要求立即办理人工账号申诉核验",vo.handoffDetail());assertNull(vo.suggestion());
        verify(aiChatService).chat(anyString(),anyString());
    }
    @Test void explainingManualProcedureDoesNotItselfRequireHumanWork() {
        explanationSource();givenModelReturnsRaw(decision(false,"ANSWERED","NONE",""));
        var vo=KnowledgeAnswerVO.of(ragAnswerService.answer("管理员审核与索引有什么区别？"));
        assertFalse(vo.needHuman());assertEquals("ANSWER",vo.nextAction());verify(aiChatService).chat(anyString(),anyString());
    }
    @Test void repairTransportFailureIsNotFalselyReportedAsAnswerOrClarification() {
        explanationSource();when(aiChatService.chat(anyString(),anyString())).thenReturn(
                AiCallResult.ok(decision(true,"ANSWERED","NONE",""),1,"fixture"),
                AiCallResult.failed(ErrorCode.AI_CALL_FAILED,"upstream_transport",1));
        var vo=KnowledgeAnswerVO.of(ragAnswerService.answer("PUT规则"));
        assertTrue(vo.needHuman());assertEquals("MODEL_TRANSPORT_FAILURE",vo.handoffReason());assertEquals("HUMAN_SUPPORT",vo.nextAction());
        assertNull(vo.suggestion());verify(aiChatService,times(2)).chat(anyString(),anyString());
    }

    @Test
    @DisplayName("向量路高分命中：matched=true、needHuman=false，置信度取向量路最高相似度，并生成建议与相似问题")
    void highConfidenceIsAnswerable() {
        KnowledgeChunk target = chunk("faq/01-账户问题.md", "如何重置登录密码", 0, "在登录页点击忘记密码，用注册手机号重置");
        KnowledgeChunk other = chunk("faq/01-账户问题.md", "如何更换绑定手机号", 1, "在安全中心更换绑定手机号");

        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(target, 0.0328, EnumSet.of(RetrievalSource.VECTOR, RetrievalSource.KEYWORD)),
                        new FusedHit(other, 0.0161, EnumSet.of(RetrievalSource.VECTOR))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR,
                                List.of(new RetrievalHit(target, 0.81), new RetrievalHit(other, 0.72)), 300),
                        RetrievalResult.ok(RetrievalSource.KEYWORD,
                                List.of(new RetrievalHit(target, 0.3636)), 4)),
                305));
        givenModelReturns("引导用户在登录页点击忘记密码，用注册手机号重置密码",
                "登录时提示验证码异常怎么办？", "账号被锁定后如何解锁？");

        RagAnswer answer = ragAnswerService.answer("怎么重置密码");

        assertTrue(answer.matched());
        assertFalse(answer.needHuman());
        assertEquals(0.81, answer.confidence(), "置信度应取向量路 top1，而不是融合分 0.0328");
        assertEquals(2, answer.sources().size());

        RagSource first = answer.sources().get(0);
        assertEquals("faq/01-账户问题.md", first.sourceFile());
        assertEquals("如何重置登录密码", first.sectionTitle());
        assertEquals(0.81, first.score());
        assertEquals(Set.of(RetrievalSource.VECTOR, RetrievalSource.KEYWORD), first.via());
        assertEquals(0.72, answer.sources().get(1).score(), "第2条只被向量路召回，分数同样来自向量路");

        assertEquals("引导用户在登录页点击忘记密码，用注册手机号重置密码", answer.suggestion());
        assertEquals(List.of("如何重置登录密码", "如何更换绑定手机号"), answer.similarQuestions());
        assertTrue(answer.degradedSources().isEmpty());
        assertTrue(answer.costMs() >= 0, "总耗时应当被记录下来");
    }

    @Test
    @DisplayName("送往模型的 user 提示词携带切片的完整正文作为依据，而不是给人看的截断摘录")
    void promptCarriesFullChunkContentAsGrounding() {
        String fullContent = "第一步：点击忘记密码。第二步：输入注册手机号获取验证码。";
        KnowledgeChunk target = chunk("faq/01-账户问题.md", "如何重置登录密码", 0, fullContent);

        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(target, 0.0328, EnumSet.of(RetrievalSource.VECTOR))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR, List.of(new RetrievalHit(target, 0.81)), 200),
                        RetrievalResult.ok(RetrievalSource.KEYWORD, List.of(), 2)),
                205));
        givenModelReturns("引导用户自助重置密码", "忘记密码怎么办？");

        ragAnswerService.answer("怎么重置密码");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiChatService).chat(anyString(), userPrompt.capture());
        assertTrue(userPrompt.getValue().contains(fullContent), "依据必须是切片原文，模型才能据此作答");
        assertTrue(userPrompt.getValue().contains("如何重置登录密码"), "资料块应带小节标题，便于定位");
        assertTrue(userPrompt.getValue().contains("怎么重置密码"), "提示词里应包含用户问题");
    }

    // ------------------------------------------------------------ 低置信度：转人工且不调模型

    @ParameterizedTest
    @ValueSource(doubles = {0.5752178520, 0.5989091154011912, 0.6412421128172787, 0.6517207180621484})
    void recalibratedDefaultAllowsPreviouslyBlockedQueries(double score) {
        KnowledgeChunk target = chunk("faq/05-项目权限与AI处理.md", "哪些账号可以查看和修改工单", 0,
                "处理人只能访问指派给自己的工单。");
        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(target, 0.0164, EnumSet.of(RetrievalSource.VECTOR))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR, List.of(new RetrievalHit(target, score)), 1)), 1));
        givenModelReturns("处理人只能访问指派给自己的工单", "哪些账号可以查看工单");
        var answer=ragAnswerService.answer("处理人能读取未指派给自己的工单吗");
        assertEquals(0.55, new KnowledgeVectorProperties().getConfidenceThreshold());
        assertFalse(answer.needHuman());
        assertNotNull(answer.suggestion());
        verify(aiChatService).chat(anyString(), anyString());
    }

    @Test
    void nw02PasswordResetReachesModelWithFullEvidenceAtItsHistoricalScore() {
        String content = "模拟客服平台：点击忘记密码，输入注册手机号获取短信验证码，验证后设置8到20位字母与数字的新密码。";
        KnowledgeChunk target = chunk("faq/01-账户问题.md", "如何重置登录密码", 0, content);
        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(target, 0.0164, EnumSet.of(RetrievalSource.VECTOR))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR,
                        List.of(new RetrievalHit(target, 0.5989091154011912)), 1)), 1));
        givenModelReturns("演示规则：通过忘记密码入口，用注册手机号验证码重置密码；当前项目未实现短信功能。",
                "如何重置登录密码");

        RagAnswer answer = ragAnswerService.answer("演示账号忘了口令，但原手机卡还能收短信，资料给出哪条重置路径？");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiChatService).chat(anyString(), userPrompt.capture());
        assertTrue(userPrompt.getValue().contains(content));
        assertFalse(answer.needHuman());
        assertEquals("ANSWERED", answer.statusReason());
        assertTrue(answer.suggestion().contains("忘记密码"));
        assertTrue(answer.suggestion().contains("非本项目实际能力"));
    }

    @Test
    @DisplayName("分数低于阈值：仍算 matched（找到了沾边内容），但转人工，且根本不调用模型")
    void lowConfidenceGoesToHumanWithoutCallingModel() {
        KnowledgeChunk target = chunk("faq/02-支付问题.md", "如何申请开具发票", 0, "在订单中申请发票");

        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(target, 0.0164, EnumSet.of(RetrievalSource.VECTOR))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR, List.of(new RetrievalHit(target, 0.5414)), 250),
                        RetrievalResult.ok(RetrievalSource.KEYWORD, List.of(), 2)),
                255));

        RagAnswer answer = ragAnswerService.answer("如何修改发票抬头");

        assertTrue(answer.matched(), "找到了候选内容，matched 应为 true");
        assertTrue(answer.needHuman(), "但 0.5414 < 当前阈值，必须转人工");
        assertEquals(0.5414, answer.confidence());
        assertNull(answer.suggestion(), "转人工时不得给出 AI 自拟的建议");
        assertTrue(answer.similarQuestions().isEmpty());
        verify(aiChatService, never()).chat(anyString(), anyString());
    }

    @Test
    @DisplayName("置信度恰好等于阈值算通过（判定用 <，不用 <=）")
    void confidenceEqualToThresholdIsAnswerable() {
        KnowledgeChunk target = chunk("faq/03-功能异常.md", "上传附件失败怎么办", 0, "单个附件不得超过 10MB");

        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(target, 0.0164, EnumSet.of(RetrievalSource.VECTOR))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR, List.of(new RetrievalHit(target, THRESHOLD)), 200),
                        RetrievalResult.ok(RetrievalSource.KEYWORD, List.of(), 2)),
                205));
        givenModelReturns("提示用户单个附件不得超过 10MB", "上传附件格式支持哪些？");

        RagAnswer answer = ragAnswerService.answer("上传附件失败");

        assertFalse(answer.needHuman());
        assertEquals(THRESHOLD, answer.confidence());
    }

    @Test
    @DisplayName("向量路降级：置信度记 0 并必然转人工，明确指出是哪一路挂了，且不调用模型")
    void degradedVectorPathForcesHuman() {
        KnowledgeChunk target = chunk("faq/03-功能异常.md", "上传附件失败怎么办", 0, "单个附件不得超过 10MB");

        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(target, 0.0164, EnumSet.of(RetrievalSource.KEYWORD))),
                List.of(RetrievalResult.degraded(RetrievalSource.VECTOR,
                                ErrorCode.VECTOR_MODEL_MISMATCH, "库中向量与当前模型不一致", 8),
                        RetrievalResult.ok(RetrievalSource.KEYWORD, List.of(new RetrievalHit(target, 0.25)), 3)),
                12));

        RagAnswer answer = ragAnswerService.answer("上传附件失败");

        assertEquals(0.0, answer.confidence(), "取不到向量分时不猜，记 0");
        assertTrue(answer.needHuman());
        assertEquals(List.of(RetrievalSource.VECTOR), answer.degradedSources(),
                "必须报出降级的那一路，否则分不清'库里没有'与'那一路挂了'");

        RagSource only = answer.sources().get(0);
        assertEquals(0.0, only.score(), "关键词路独有的来源没有向量分，记 0");
        assertEquals(Set.of(RetrievalSource.KEYWORD), only.via(), "via 说明它来自关键词路，避免 score=0 被误读为'质量为零'");

        verify(aiChatService, never()).chat(anyString(), anyString());
    }

    @Test
    @DisplayName("两条路都没命中：matched=false、needHuman=true、来源为空、不调用模型")
    void noHitIsNotMatched() {
        givenSearch(new KnowledgeSearchResult(
                List.of(),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR, List.of(), 200),
                        RetrievalResult.ok(RetrievalSource.KEYWORD, List.of(), 2)),
                205));

        RagAnswer answer = ragAnswerService.answer("公司年会在哪里举办");

        assertFalse(answer.matched());
        assertTrue(answer.needHuman());
        assertEquals(0.0, answer.confidence());
        assertTrue(answer.sources().isEmpty());
        verify(aiChatService, never()).chat(anyString(), anyString());
    }

    // ------------------------------------------------------------ 生成失败与内容不合规

    @Test
    @DisplayName("模型调用失败：建议为空，明确转人工，来源照常返回")
    void modelFailureStillReturnsSources() {
        KnowledgeChunk target = chunk("faq/01-账户问题.md", "如何重置登录密码", 0, "在登录页点击忘记密码");

        givenSearch(highConfidenceSearch(target));
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.failed(ErrorCode.AI_CALL_FAILED, "connect timed out", 3000));

        RagAnswer answer = ragAnswerService.answer("怎么重置密码");

        assertNull(answer.suggestion(), "生成失败时不给半成品建议");
        assertTrue(answer.similarQuestions().isEmpty());
        assertTrue(answer.needHuman(), "模型生成失败时明确转人工");
        assertEquals(1, answer.sources().size(), "来源照常返回，人工仍有依据可用");
    }

    @Test
    @DisplayName("模型答了但不守规矩（不是 JSON）：整份内容作废，但来源照常返回")
    void unparsableModelOutputYieldsNoContent() {
        KnowledgeChunk target = chunk("faq/01-账户问题.md", "如何重置登录密码", 0, "在登录页点击忘记密码");

        givenSearch(highConfidenceSearch(target));
        // 模型没按 JSON 格式输出——这正是"答了但不守规矩"，与"没答话"要分开
        givenModelReturnsRaw("建议：引导用户重置密码。");

        RagAnswer answer = ragAnswerService.answer("怎么重置密码");

        assertNull(answer.suggestion());
        assertTrue(answer.similarQuestions().isEmpty(), "一个字段不合规就整份作废，不留半份内容");
        assertTrue(answer.needHuman());
        assertEquals(1, answer.sources().size());
    }

    @Test
    @DisplayName("模型返回空内容时不给空建议")
    void blankModelOutputBecomesNullSuggestion() {
        KnowledgeChunk target = chunk("faq/01-账户问题.md", "如何重置登录密码", 0, "在登录页点击忘记密码");

        givenSearch(highConfidenceSearch(target));
        givenModelReturnsRaw("   \n  ");

        RagAnswer answer = ragAnswerService.answer("怎么重置密码");

        assertNull(answer.suggestion());
        assertTrue(answer.similarQuestions().isEmpty());
    }

    @Test
    @DisplayName("模型把建议写成多行时，被折叠成一行；相似问题同样被折叠")
    void multiLineContentIsFoldedToOneLine() {
        KnowledgeChunk target = chunk("faq/01-账户问题.md", "如何重置登录密码", 0, "在登录页点击忘记密码");

        givenSearch(highConfidenceSearch(target));
        givenModelReturns("先让用户把系统时间改为自动获取，\n\n  再重新获取验证码重试",
                "时间不对会导致什么？", "  验证码收不到\n怎么办？  ");

        RagAnswer answer = ragAnswerService.answer("验证码错误");

        assertEquals("先让用户把系统时间改为自动获取， 再重新获取验证码重试", answer.suggestion());
        assertFalse(answer.suggestion().contains("\n"));
        assertEquals(List.of("如何重置登录密码"), answer.similarQuestions());
    }

    // ------------------------------------------------------------ 摘录

    @Test
    @DisplayName("来源摘要折叠空白成一行，并截断到 150 字")
    void summaryIsFoldedAndTruncated() {
        String content = "第一行\n\n  第二行\t第三行 " + "补".repeat(200);
        KnowledgeChunk target = chunk("faq/01-账户问题.md", "如何重置登录密码", 0, content);

        givenSearch(highConfidenceSearch(target));
        givenModelReturns("建议内容", "相似问题？");

        String summary = ragAnswerService.answer("重置密码").sources().get(0).summary();

        assertEquals(151, summary.length(), "150 个字 + 省略号");
        assertTrue(summary.endsWith("…"));
        assertFalse(summary.contains("\n"), "换行必须被折叠，否则会塞进返回体");
        assertFalse(summary.contains("  "), "连续空白必须被折叠");
    }

    @Test
    void demoAnswerAlwaysExposesProjectCapabilityBoundary() {
        givenSearch(highConfidenceSearch(chunk("faq/01-账户问题.md","账号被锁定如何解锁",0,"演示规则等待30分钟")));
        givenModelReturns("演示规则：先等待30分钟再重试", "账号被锁定如何解锁");
        var answer=ragAnswerService.answer("模拟账号锁定怎么办");
        assertTrue(answer.suggestion().startsWith("演示规则（非本项目实际能力）："));
        assertTrue(answer.suggestion().contains("等待30分钟"));
        assertFalse(answer.needHuman());
    }

    @Test
    void projectAnswerIsNotRelabelledAsUnimplemented() {
        givenSearch(highConfidenceSearch(chunk("faq/05-项目权限与AI处理.md","哪些账号可以查看和修改工单",0,"提交人只读自己的工单")));
        givenModelReturns("提交人只能访问自己的工单", "哪些账号可以查看和修改工单");
        assertEquals("提交人只能访问自己的工单",ragAnswerService.answer("别人能查我的单吗").suggestion());
    }

    @Test
    void scopeNoticeMustNotTruncateLongAdvice() {
        givenSearch(highConfidenceSearch(chunk("faq/01-账户问题.md","模拟问题",0,"参考资料")));
        givenModelReturns("演示规则："+"文".repeat(194), "模拟问题");
        var answer=ragAnswerService.answer("模拟问题");
        assertNull(answer.suggestion());
        assertTrue(answer.needHuman());
        assertTrue(answer.similarQuestions().isEmpty());
    }

    // ------------------------------------------------------------ 夹具

    @Test
    void unsupportedWorkaroundIsHiddenAndExplained() {
        givenSearch(highConfidenceSearch(chunk("faq/03-功能异常.md", "上传附件失败怎么办", 0, "单个附件不超过10MB，检查格式和文件名。")));
        givenModelReturns("演示规则：将15MB文件压缩或拆分后上传", "上传附件失败怎么办");
        var answer=ragAnswerService.answer("15MB文件如何上传");
        assertNull(answer.suggestion());
        assertTrue(answer.needHuman());
        assertEquals("UNSUPPORTED_WORKAROUND",answer.statusReason());
        assertFalse(answer.sources().isEmpty());
    }

    @Test
    void explicitlyDocumentedWorkaroundRemainsAvailable() {
        givenSearch(highConfidenceSearch(chunk("faq/03-功能异常.md", "上传附件失败怎么办", 0, "模拟场景可将文件压缩到10MB以内再上传。")));
        givenModelReturns("演示规则：可压缩到10MB以内再上传", "上传附件失败怎么办");
        var answer=ragAnswerService.answer("15MB文件如何上传");
        assertFalse(answer.needHuman());
        assertEquals("ANSWERED",answer.statusReason());
    }

    @ParameterizedTest
    @ValueSource(strings={"upstream_timeout: socket timed out", "upstream_transport: TLS EOF"})
    void upstreamFailuresAreNotReportedAsThresholdAbstention(String failure) {
        givenSearch(highConfidenceSearch(chunk("faq/02-支付问题.md", "支付超时", 0, "查询订单状态。")));
        when(aiChatService.chat(anyString(),anyString())).thenReturn(AiCallResult.failed(ErrorCode.AI_CALL_FAILED,failure,15000));
        var answer=ragAnswerService.answer("支付超时怎么办");
        assertTrue(answer.needHuman());
        assertNull(answer.suggestion());
        assertEquals(failure.startsWith("upstream_timeout") ? "MODEL_TIMEOUT" : "MODEL_TRANSPORT_FAILURE",answer.statusReason());
        assertEquals(answer.statusReason(), com.smartticket.vo.KnowledgeAnswerVO.of(answer).statusReason());
    }

    @Test
    void negativeWarningDoesNotBecomeAnInventedRecommendation() {
        givenSearch(highConfidenceSearch(chunk("faq/03-功能异常.md", "上传附件失败怎么办", 0, "单个附件不超过10MB。")));
        givenModelReturns("演示规则：15MB超过上限，不要压缩或拆分绕过限制", "上传附件失败怎么办");
        var answer=ragAnswerService.answer("15MB附件能上传吗");
        assertFalse(answer.needHuman());
        assertEquals("ANSWERED",answer.statusReason());
    }

    @Test
    void affirmativeWorkaroundAfterNegativeClauseIsStillBlocked() {
        givenSearch(highConfidenceSearch(chunk("faq/03-功能异常.md", "上传附件失败怎么办", 0, "单个附件不超过10MB。")));
        givenModelReturns("演示规则：不要绕过权限，但可以压缩文件上传", "上传附件失败怎么办");
        var answer=ragAnswerService.answer("15MB附件能上传吗");
        assertTrue(answer.needHuman());
        assertEquals("UNSUPPORTED_WORKAROUND",answer.statusReason());
    }

    @Test
    void lowScoreRecommendationsAreRemovedWithoutChangingModelContextOrSources() {
        KnowledgeChunk target = chunk("resolved-ticket/demo/v1.md", "合成演示蓝灯巡检如何恢复", 0, "先核对租约标记，再重新排队并查看索引回执。");
        KnowledgeChunk whiteScreen = chunk("tickets/03-功能异常.md", "打开工单详情页白屏", 1, "记录白屏请求。");
        KnowledgeChunk captcha = chunk("tickets/01-账户问题.md", "登录验证码错误", 2, "检查验证码。");
        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(target, 0.03, EnumSet.of(RetrievalSource.VECTOR)),
                        new FusedHit(whiteScreen, 0.02, EnumSet.of(RetrievalSource.VECTOR)),
                        new FusedHit(captcha, 0.01, EnumSet.of(RetrievalSource.VECTOR))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR,
                        List.of(new RetrievalHit(target, 0.7475813482), new RetrievalHit(whiteScreen, 0.4724071432),
                                new RetrievalHit(captcha, 0.4679955359)), 1)), 1));
        givenModelReturns("先核对租约标记，再重新排队并查看索引回执", "打开工单详情页白屏", "登录验证码错误");

        RagAnswer answer = ragAnswerService.answer("演示巡检蓝色校验灯先核对什么");

        assertEquals(List.of(target.sectionTitle()), answer.similarQuestions());
        assertEquals(3, answer.sources().size());
        assertFalse(answer.needHuman());
        assertEquals("ANSWERED", answer.statusReason());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiChatService).chat(anyString(), prompt.capture());
        assertTrue(prompt.getValue().contains(whiteScreen.content()));
        assertTrue(prompt.getValue().contains(captcha.content()));
    }

    @Test
    void recommendationFloorIsInclusiveAndTitlesAreDeduplicatedAfterFiltering() {
        KnowledgeChunk target = chunk("faq/example.md", "相关问题甲", 0, "资料甲");
        KnowledgeChunk duplicate = chunk("faq/example.md", "相关问题甲", 1, "资料甲续片");
        KnowledgeChunk boundary = chunk("faq/example.md", "相关问题乙", 2, "资料乙");
        KnowledgeChunk below = chunk("faq/example.md", "低分问题", 3, "低分资料");
        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(target, 0.04, EnumSet.of(RetrievalSource.VECTOR)),
                        new FusedHit(duplicate, 0.03, EnumSet.of(RetrievalSource.VECTOR)),
                        new FusedHit(boundary, 0.02, EnumSet.of(RetrievalSource.VECTOR)),
                        new FusedHit(below, 0.01, EnumSet.of(RetrievalSource.VECTOR))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR,
                        List.of(new RetrievalHit(target, .8), new RetrievalHit(duplicate, .7),
                                new RetrievalHit(boundary, THRESHOLD), new RetrievalHit(below, THRESHOLD - .000001)), 1)), 1));
        givenModelReturns("依照已有资料核对", "模型自行拟定的问题");
        assertEquals(List.of("相关问题甲", "相关问题乙"), ragAnswerService.answer("核对资料").similarQuestions());
    }

    @Test
    void keywordOnlyTitleIsNotRecommendedAsIfItHadVectorEvidence() {
        KnowledgeChunk target = chunk("faq/example.md", "相关问题", 0, "完整依据");
        KnowledgeChunk keywordOnly = chunk("faq/keyword.md", "仅关键词命中的标题", 0, "辅助资料");
        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(target, .02, EnumSet.of(RetrievalSource.VECTOR)),
                        new FusedHit(keywordOnly, .01, EnumSet.of(RetrievalSource.KEYWORD))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR, List.of(new RetrievalHit(target, .8)), 1),
                        RetrievalResult.ok(RetrievalSource.KEYWORD, List.of(new RetrievalHit(keywordOnly, .9)), 1)), 1));
        givenModelReturns("使用资料中的核对流程", "相关问题");
        RagAnswer answer = ragAnswerService.answer("核对流程");
        assertEquals(List.of("相关问题"), answer.similarQuestions());
        assertEquals(2, answer.sources().size());
        assertFalse(answer.needHuman());
    }

    @Test
    void noEligibleRecommendationDoesNotSuppressAnOtherwiseGeneratedAnswer() {
        KnowledgeChunk vectorCandidate = chunk("faq/vector.md", "未进入融合结果的候选", 0, "候选资料");
        KnowledgeChunk selected = chunk("faq/keyword.md", "关键词资料的标题", 0, "可以用于解释的资料");
        givenSearch(new KnowledgeSearchResult(
                List.of(new FusedHit(selected, .02, EnumSet.of(RetrievalSource.KEYWORD))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR, List.of(new RetrievalHit(vectorCandidate, .8)), 1),
                        RetrievalResult.ok(RetrievalSource.KEYWORD, List.of(new RetrievalHit(selected, .9)), 1)), 1));
        givenModelReturns("按已有资料核对具体条件", "关键词资料的标题");
        RagAnswer answer = ragAnswerService.answer("核对条件");
        assertTrue(answer.similarQuestions().isEmpty());
        assertFalse(answer.needHuman());
        assertEquals("ANSWERED", answer.statusReason());
        assertNotNull(answer.suggestion());
        assertEquals(1, answer.sources().size());
    }

    private static KnowledgeSearchResult highConfidenceSearch(KnowledgeChunk target) {
        return new KnowledgeSearchResult(
                List.of(new FusedHit(target, 0.0328, EnumSet.of(RetrievalSource.VECTOR))),
                List.of(RetrievalResult.ok(RetrievalSource.VECTOR, List.of(new RetrievalHit(target, 0.81)), 200),
                        RetrievalResult.ok(RetrievalSource.KEYWORD, List.of(), 2)),
                205);
    }

    private static KnowledgeChunk chunk(String sourceFile, String sectionTitle, int chunkIndex, String content) {
        return new KnowledgeChunk(sourceFile, "文档标题", sectionTitle, chunkIndex, content);
    }

}
