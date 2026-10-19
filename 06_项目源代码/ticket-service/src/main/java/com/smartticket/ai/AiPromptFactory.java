package com.smartticket.ai;

import com.smartticket.enums.TicketCategory;
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
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Prompt 工厂：把模板文件渲染成可直接发给模型的 system / user 文本（阶段2 第2天）。
 *
 * <p>三个设计点，每个都是踩过或差点踩到的坑：</p>
 *
 * <h3>1. 分隔符必须从 {@code {}} 改成 {@code <>}</h3>
 * Spring AI 的 {@code PromptTemplate} 默认用 {@code {}} 作占位符分隔符，而分类 Prompt 里
 * <b>必然含 JSON 输出示例</b>（示例本身就是花括号）。用默认分隔符时，StringTemplate 会把
 * {@code {"category":...}} 当成占位符去解析并直接抛错——<b>模板加载即失败，不是渲染失败</b>。
 * 改成 {@code <>} 后花括号成为普通字符，JSON 示例可以原样写在模板里。
 *
 * <h3>2. 模板首行的版本注释在渲染时被剥离</h3>
 * 版本头是给人看的元信息，发给模型只会白花 token、并可能干扰指令遵循。
 *
 * <h3>3. 分类清单从 {@link TicketCategory} 渲染，不在模板里手写</h3>
 * 这与 {@link AiClassificationParser} 的校验共用同一份清单，杜绝
 * "Prompt 里加了一类、校验白名单忘了加"（或反过来）这种两处真值漂移。
 *
 * <p>模板在构造期加载并渲染 system 文本：分类清单是静态的，没必要每次调用重渲染；
 * 更重要的是让"模板文件缺失或语法错误"在<b>应用启动时</b>就暴露，而不是等到第一次分类请求。</p>
 */
@Component
public class AiPromptFactory {

    /**
     * 当前 Prompt 版本号。
     *
     * <p>必须与模板文件首行 {@code <!-- prompt-version: xxx -->} 完全一致。
     * 一致性由 {@code AiPromptFactoryTest} 断言守护——对应阶段2 约束第 10 条
     * 「每次 Prompt 修改都要记录版本」，把"记得改版本号"从人工纪律变成可自动检查的约束。</p>
     */
    public static final String PROMPT_VERSION = "ticket-classify-v1";

    private static final String SYSTEM_TEMPLATE = "prompts/ticket-classify-system.md";

    private static final String USER_TEMPLATE = "prompts/ticket-classify-user.md";

    /** 模板里的 HTML 注释（版本头），渲染时剥离 */
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");

    /** 描述为空时占位。留空会让模型面对一个空的【工单描述】栏目，显式写"无"更不易被忽略 */
    private static final String BLANK_PLACEHOLDER = "（无）";

    private static final TemplateRenderer RENDERER = StTemplateRenderer.builder()
            .startDelimiterToken('<')
            .endDelimiterToken('>')
            .build();

    private final String systemTemplate;

    private final String userTemplate;

    /** system 文本渲染一次即可：分类清单是静态的 */
    private final String renderedSystemPrompt;

    public AiPromptFactory() {
        this.systemTemplate = loadTemplate(SYSTEM_TEMPLATE);
        this.userTemplate = loadTemplate(USER_TEMPLATE);
        this.renderedSystemPrompt = render(systemTemplate, Map.of("categories", categoryList()));
    }

    /**
     * 送往模型的描述长度上限（字符）。
     *
     * <p>接口层允许描述到 65535 字（与 {@code description TEXT} 列对齐），但"收得下"
     * 不等于"该全发给模型"：分类只需要开头的关键信息，长尾细节对分类的边际价值极低，
     * 而 token 成本随长度线性增长。2000 字（中文约 2000+ tokens）足以覆盖绝大多数工单描述，
     * 同时把最坏情况的成本钉死。</p>
     */
    static final int MAX_DESCRIPTION_CHARS = 2000;

    /**
     * 截断标记。
     *
     * <p>刻意显式告知模型"描述被截断了"，而不是悄悄截掉：v1 Prompt 里要求"信息不足时降低
     * confidence"，模型只有知道信息不完整，才会给出与之相符的较低分值。悄悄截断会让它
     * 以为描述就这么短，从而给出虚高的把握度。</p>
     */
    private static final String TRUNCATED_SUFFIX = "…（描述过长，此处已截断）";

    /** 分类用的 system 提示词 */
    public String classificationSystemPrompt() {
        return renderedSystemPrompt;
    }

    /**
     * 分类用的 user 提示词。
     *
     * @param title       工单标题
     * @param description 工单描述，可为空；超长时按 {@link #MAX_DESCRIPTION_CHARS} 截断
     */
    public String classificationUserPrompt(String title, String description) {
        return render(userTemplate, Map.of(
                "title", orBlankPlaceholder(title),
                "description", orBlankPlaceholder(truncateDescription(description))));
    }

    // ------------------------------------------------------------ 内部

    /**
     * 渲染模板。
     *
     * <p>每次调用新建 {@link PromptTemplate} 而不是复用同一个实例：本类的实例是单例、
     * 会被并发调用，而 PromptTemplate 的变量集合是可变的，复用存在线程安全疑虑。
     * 一次对象分配换取"不必去读框架源码确认它是否线程安全"，这笔账划算。</p>
     *
     * <p>关于注入内容里的 {@code <>} 字符：StringTemplate 只解析模板文本，不会对替换进去的
     * 变量值做二次解析，因此标题里即使出现 {@code <script>} 也只会被当作普通文本。
     * 这一点由测试固定住，避免将来换模板引擎时静默失效。</p>
     */
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
            // 模板是随应用打包的静态资源，读不到说明打包或路径有问题，属启动期硬故障，
            // 必须让应用起不来而不是降级——否则会变成"每次分类都返回未配置"的静默故障
            throw new UncheckedIOException("加载 Prompt 模板失败：" + path, e);
        }
    }

    /** 渲染成多行清单，供模板中的 categories 占位使用 */
    private String categoryList() {
        return TicketCategory.allLabels().stream()
                .map(label -> "- " + label)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String orBlankPlaceholder(String value) {
        return StringUtils.hasText(value) ? value : BLANK_PLACEHOLDER;
    }

    /**
     * 截断超长描述。
     *
     * <p>先 {@code strip()} 再判断长度：结尾一大段空白不应该"占满"额度而把有效内容挤出去。</p>
     */
    private String truncateDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.strip();
        if (trimmed.length() <= MAX_DESCRIPTION_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_DESCRIPTION_CHARS) + TRUNCATED_SUFFIX;
    }

}
