package com.smartticket.rag;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * RAG 回答的 Prompt 工厂（阶段2 第5周任务 6）。
 *
 * <p>把「用户问题 + 检索到的知识库资料」渲染成发给模型的 system / user 文本。</p>
 *
 * <h3>与 {@code com.smartticket.ai.AiPromptFactory} 的关系</h3>
 * 两者是<b>两套独立的 Prompt 家族</b>，各有各的版本号与生命周期，所以各自一个工厂：
 * 分类 Prompt 是长期稳定的输出契约，RAG 回答 Prompt 会随检索效果反复调。
 * 若共用一个版本常量，调 RAG 措辞会被迫给分类 Prompt"陪跑"一次版本升级，
 * 版本号就失去了"这一版改了什么"的意义。
 *
 * <p><b>但下面的两条设置必须与 AiPromptFactory 保持一致</b>，两处的理由相同：</p>
 * <ol>
 *   <li>分隔符用 {@code <>} 而不是默认的 {@code {}}——否则正文里的花括号会被当成占位符；</li>
 *   <li>渲染时剥离首行的 {@code <!-- prompt-version -->} 注释——那是给人看的元信息，
 *       发给模型只会白花 token。</li>
 * </ol>
 * 改其中一处时请连同另一处一起检查（这两条是踩过的坑，不是风格偏好）。
 *
 * <h3>资料不足时不调模型</h3>
 * 本工厂只负责渲染。**"该不该调模型"由 {@code RagAnswerServiceImpl} 决定**：
 * 置信度不足时它压根不会走到这里，但主题相似不代表资料覆盖问题，因此模型仍须通过 needHuman 追加拒答；
 * 模型不能解除服务层的低置信度拦截（见 {@link RagAnswer#needHuman()}）。
 */
@Component
public class RagAnswerPromptFactory {

    /**
     * 当前 Prompt 版本号。
     *
     * <p>必须与两个模板首行 {@code <!-- prompt-version: xxx -->} 完全一致，
     * 由 {@code RagAnswerPromptFactoryTest} 断言守护（阶段2 约束第 10 条：
     * 每次 Prompt 修改都要记录版本）。</p>
     */
    public static final String PROMPT_VERSION = "rag-answer-v4.0";

    private static final String SYSTEM_TEMPLATE = "prompts/rag-answer-system.md";

    private static final String USER_TEMPLATE = "prompts/rag-answer-user.md";

    /** 模板里的 HTML 注释（版本头），渲染时剥离 */
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");

    private static final String BLANK_PLACEHOLDER = "（无）";

    private static final TemplateRenderer RENDERER = StTemplateRenderer.builder()
            .startDelimiterToken('<')
            .endDelimiterToken('>')
            .build();

    /** system 文本是静态的：渲染一次即可 */
    private final String renderedSystemPrompt;

    private final String userTemplate;

    public RagAnswerPromptFactory() {
        String systemTemplate = loadTemplate(SYSTEM_TEMPLATE);
        this.userTemplate = loadTemplate(USER_TEMPLATE);
        this.renderedSystemPrompt = render(systemTemplate, Map.of());
    }

    /** RAG 回答用的 system 提示词 */
    public String systemPrompt() {
        return renderedSystemPrompt;
    }

    /**
     * RAG 回答用的 user 提示词。
     *
     * @param query           用户问题
     * @param groundingChunks 作为依据的切片，<b>按融合分降序</b>（最相关的在前）。
     *                        用的是切片的完整正文，不是给人看的摘录——模型需要原文才能作答
     */
    public String userPrompt(String query, List<KnowledgeChunk> groundingChunks) {
        return render(userTemplate, Map.of(
                "query", StringUtils.hasText(query) ? query : BLANK_PLACEHOLDER,
                "sources", renderSources(groundingChunks)));
    }

    // ------------------------------------------------------------ 内部

    /**
     * 把切片渲染成带编号的资料块。
     *
     * <p>编号是必要的：模型在长上下文里更容易"跟住"带编号的条目，
     * 也便于出错时按编号回查是哪一条资料被用错了。</p>
     */
    private static String renderSources(List<KnowledgeChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            builder.append("【资料 ").append(i + 1).append('】').append(chunk.sectionTitle()).append('\n')
                    .append("【资料性质】").append(KnowledgeProvenance.scope(chunk.sourceFile(), chunk.content())).append('\n')
                    .append(chunk.content().strip()).append('\n');
            if (i < chunks.size() - 1) {
                builder.append('\n');
            }
        }
        return builder.toString().strip();
    }

    private String render(String template, Map<String, Object> variables) {
        return PromptTemplate.builder()
                .template(template)
                .renderer(RENDERER)
                .build()
                .render(variables);
    }

    private String loadTemplate(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try {
            String raw = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            return HTML_COMMENT.matcher(raw).replaceAll("").strip();
        } catch (IOException e) {
            // 与 AiPromptFactory 同一判断：模板是随包发布的静态资源，读不到属启动期硬故障。
            // 降级的后果是"每次回答都没有建议"，那种静默故障比启动失败更难发现
            throw new UncheckedIOException("加载 Prompt 模板失败：" + path, e);
        }
    }

}
