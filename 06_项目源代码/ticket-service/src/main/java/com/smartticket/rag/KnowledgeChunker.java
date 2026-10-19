package com.smartticket.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档切分器：把一份 Markdown 语料切成可检索的切片（阶段2 第4天，章程第4周任务 3）。
 *
 * <h3>切分层次：三级，依次退化</h3>
 * <pre>
 * 1. 二级标题 ##      → 一个 ## 小节 = 一个逻辑单元（FAQ 的一条问答、历史工单的一条记录）
 * 2. 空行分段         → 小节超长时，按自然段聚合装箱
 * 3. 句末标点断句     → 段落仍超长时，在「。！？；」之后断开，不在句子中间切
 *    （最后兜底：单句本身就超长时按字符硬切）
 * </pre>
 *
 * <p>为什么不直接按固定字符数滑窗切：中文没有空格分词，机械切片极易把一句话劈成两半，
 * 于是"前半句"和"后半句"各自都检索不到完整语义，召回质量直接受损。语料本身已经有
 * {@code ##} 这个天然语义边界，先用它、再用标点，是最省事也最不容易出错的做法。</p>
 *
 * <h3>关于文档前言</h3>
 * 一级标题 {@code #} 作为文档标题存入元数据；<b>一级标题与第一个 {@code ##} 之间的内容不参与切分</b>。
 * 语料各文件开头都有"语料性质：演示语料"的声明，它属于给人看的元信息：
 * 若被切成切片，既会污染检索（用户问"如何重置密码"却召回一段声明），又白白占用向量空间。
 *
 * <h3>关于重叠（overlap）</h3>
 * 同一小节被切成多块时，后一块的开头会带上前一块结尾的一小段。这是 RAG 的常规做法：
 * 一个完整的论述可能跨在切分点上，重叠能让边界处的语义在两块里都完整出现。
 * 重叠<b>只在同一个小节内部发生</b>，不会跨小节、更不会跨文档——否则会把不相关的两段内容
 * 粘在一起，制造出本来不存在的语义关联。
 *
 * <p>本类是无状态纯函数（给定输入必得同一输出），因此是可测的：
 * 参数取值依据见决策记录 {@code 20260930_RAG语料与切片策略.md}。</p>
 */
@Slf4j
@Component
public class KnowledgeChunker {

    /**
     * 单个切片的目标长度上限（字符）。
     *
     * <p>取 500 的依据：FAQ 的一条问答通常 150~350 字，一条历史工单 250~450 字，
     * 用 500 可以让绝大多数小节<b>整节一个切片</b>，不被切开——这比"参数看起来专业"更重要：
     * 切不开就不会丢语义。同时 500 字（中文约 500~700 token）在向量模型输入上限内，
     * 检索时也不会因为片段过长而稀释语义重点。</p>
     */
    static final int MAX_CHUNK_CHARS = 500;

    /**
     * 相邻切片的重叠长度上限（字符）。
     *
     * <p>取 80 约等于一到两句话，足以覆盖"跨切分点的半个论述"，又不会让相邻切片内容重复到
     * 互相抢排名。</p>
     */
    static final int OVERLAP_CHARS = 80;

    /** HTML 注释：语料里用于标注元信息，与 Prompt 模板的处理一致，不进正文 */
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");

    /**
     * 一级标题。带 {@code (?m)} 是必需的：文档标题要在整份文本上预扫描出来，
     * 不带多行标志时 {@code ^} 只匹配整段文本的开头，除"标题恰好是第一行"外一律取不到。
     * 同时用 {@code [^\n]} 限定在行内，避免 {@code \s} 把后面的换行也吃掉而匹配到别的行。
     */
    private static final Pattern DOC_TITLE = Pattern.compile("(?m)^#\\s+([^\\n]+?)\\s*$");

    private static final Pattern SECTION_TITLE = Pattern.compile("^##\\s+([^\\n]+?)\\s*$");

    /** 空行分段 */
    private static final Pattern BLANK_LINE = Pattern.compile("\\n\\s*\\n");

    /**
     * 句子：一直取到句末标点（含标点），并把其后的空白一并吞掉。
     *
     * <p>把分隔符留在句子里、把空白也带上是刻意的——这样把若干句拼回去能还原原文，
     * 不会出现"…校验失败2. 退出登录页…"这种标点与序号粘在一起的结果。</p>
     */
    private static final Pattern SENTENCE = Pattern.compile("[^。！？；!?;]*[。！？；!?;]+\\s*");

    /** 句末标点（含标点本身），用于把重叠起点对齐到句首 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？；!?;]");

    /**
     * 切分一份 Markdown 语料。
     *
     * @param sourceFile 语料文件相对路径，如 {@code faq/01-账户问题.md}
     * @param markdown   文件内容
     * @return 切片列表；文档没有任何 {@code ##} 小节时返回空列表（不抛异常）
     */
    public List<KnowledgeChunk> split(String sourceFile, String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        String text = HTML_COMMENT.matcher(markdown.replace("\r\n", "\n")).replaceAll("");
        // 文档标题先单独取出：它可能在正文任意位置，但必须作为元数据配到每一个切片上，
        // 所以不能边扫边等——扫描前定下来，后面就不必回头补
        String docTitle = resolveDocTitle(sourceFile, findDocTitle(text));

        String sectionTitle = null;
        StringBuilder sectionBody = new StringBuilder();
        List<KnowledgeChunk> chunks = new ArrayList<>();

        for (String line : text.split("\n", -1)) {
            Matcher sectionMatcher = SECTION_TITLE.matcher(line);
            if (sectionMatcher.matches()) {
                // 遇到新的 ## —— 先把上一节收尾
                flushSection(sourceFile, docTitle, sectionTitle, sectionBody, chunks);
                sectionTitle = sectionMatcher.group(1);
                sectionBody.setLength(0);
                continue;
            }

            if (sectionTitle == null) {
                // 还在文档前言区：内容丢弃（理由见类注释）
                continue;
            }

            sectionBody.append(line).append('\n');
        }

        flushSection(sourceFile, docTitle, sectionTitle, sectionBody, chunks);

        if (chunks.isEmpty()) {
            log.warn("语料未切出任何切片（缺少 ## 小节） | sourceFile={}", sourceFile);
        }
        return chunks;
    }

    // ------------------------------------------------------------ 内部

    /** 取第一个一级标题作为文档标题 */
    private String findDocTitle(String text) {
        Matcher matcher = DOC_TITLE.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 收尾一个小节：切成若干切片并追加到结果；小节内容为空则跳过 */
    private void flushSection(String sourceFile, String docTitle, String sectionTitle,
                              StringBuilder sectionBody, List<KnowledgeChunk> chunks) {
        if (sectionTitle == null) {
            return;
        }
        String body = sectionBody.toString().strip();
        if (body.isEmpty()) {
            // 只有标题、没有内容的小节不产出切片：空切片在检索里没有任何意义，
            // 却会占一条向量记录、并可能在排序时挤掉真正有用的内容
            log.debug("跳过空小节 | sourceFile={} | section={}", sourceFile, sectionTitle);
            return;
        }

        List<String> pieces = packIntoChunks(splitIntoUnits(body));
        for (int i = 0; i < pieces.size(); i++) {
            String content = i == 0 ? pieces.get(i) : overlapOf(pieces.get(i - 1)) + pieces.get(i);
            chunks.add(new KnowledgeChunk(sourceFile, docTitle, sectionTitle, chunks.size(), content));
        }
    }

    /** 把正文拆成"不可再分的最小单元"：段落 → 句子 → 字符 */
    private List<String> splitIntoUnits(String body) {
        List<String> units = new ArrayList<>();
        for (String block : BLANK_LINE.split(body)) {
            if (block.isBlank()) {
                continue;
            }
            if (block.length() <= MAX_CHUNK_CHARS) {
                units.add(block.strip());
                continue;
            }
            for (String sentence : splitIntoSentences(block)) {
                units.add(sentence.strip());
            }
        }
        // 去掉按句拆分后可能出现的空单元，并处理"单句本身就超长"的极端情况
        List<String> normalized = new ArrayList<>(units.size());
        for (String unit : units) {
            if (unit.isEmpty()) {
                continue;
            }
            normalized.addAll(hardSplit(unit));
        }
        return normalized;
    }

    /** 按句末标点切句，保留原文空白（拼回去可还原） */
    private List<String> splitIntoSentences(String block) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE.matcher(block);
        int last = 0;
        while (matcher.find()) {
            sentences.add(block.substring(last, matcher.end()));
            last = matcher.end();
        }
        if (last < block.length()) {
            sentences.add(block.substring(last));
        }
        return sentences;
    }

    /** 单句超长（例如一整段没有句末标点的长文本）时按字符硬切，保证不比上限大出太多 */
    private List<String> hardSplit(String unit) {
        if (unit.length() <= MAX_CHUNK_CHARS) {
            return List.of(unit);
        }
        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < unit.length(); start += MAX_CHUNK_CHARS) {
            pieces.add(unit.substring(start, Math.min(start + MAX_CHUNK_CHARS, unit.length())));
        }
        return pieces;
    }

    /** 贪心装箱：顺序累加单元，装不下就换下一个切片 */
    private List<String> packIntoChunks(List<String> units) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.length() > 0 && current.length() + unit.length() > MAX_CHUNK_CHARS) {
                chunks.add(current.toString().strip());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(unit);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().strip());
        }
        return chunks;
    }

    /**
     * 取上一块结尾用于重叠。
     *
     * <p>语义是"上一块结尾的若干<b>完整句子</b>，总长不超过 {@link #OVERLAP_CHARS}"。
     * 实现上从句首向后扫，取第一个满足长度条件的句末标点作为起点——它给出的是
     * 范围内最长的合法重叠。直接按字符截尾会得到从半句开始的片段，
     * 作为上下文既不通顺，还可能因为截断而改变原意。</p>
     */
    private String overlapOf(String previousChunk) {
        // 预算里先扣掉 1 个字符：重叠正文与后一块之间要插一个换行，
        // 不减它的话"重叠 80 + 换行 1 + 正文 500 = 581"会超出 MAX + OVERLAP 这个上界
        int budget = OVERLAP_CHARS - 1;

        Matcher matcher = SENTENCE_END.matcher(previousChunk);
        while (matcher.find()) {
            boolean hasTailAfter = matcher.end() < previousChunk.length();
            boolean tailWithinLimit = previousChunk.length() - matcher.end() <= budget;
            if (hasTailAfter && tailWithinLimit) {
                return previousChunk.substring(matcher.end()).strip() + "\n";
            }
        }
        // 整块找不到可用的句子边界（例如全是编号列表的片段，或超长单句被硬切的结果）：
        // 退化为按字符取尾部。有重叠总比没有好，但这种情况应当少见
        int from = Math.max(0, previousChunk.length() - budget);
        return previousChunk.substring(from).strip() + "\n";
    }

    /** 文档无一级标题时回退为文件名，保证来源信息永不为空 */
    private String resolveDocTitle(String sourceFile, String docTitle) {
        if (docTitle != null && !docTitle.isBlank()) {
            return docTitle;
        }
        int slash = sourceFile.lastIndexOf('/');
        String fileName = slash >= 0 ? sourceFile.substring(slash + 1) : sourceFile;
        return fileName.replaceFirst("\\.md$", "");
    }

}
