package com.smartticket.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 知识库语料加载器：扫描 classpath 下的语料目录，读出文件并交给切分器（阶段2 第4天）。
 *
 * <h3>为什么只扫 {@code faq/} 与 {@code tickets/} 两个子目录</h3>
 * 语料根目录下还有一份 {@code README.md}（说明语料性质与格式约定）。
 * 若把整个 {@code knowledge/} 递归扫一遍，那份说明会被当成知识库内容检索出来——
 * 用户问"如何重置密码"却召回一段"本目录文件均为演示语料"，既荒谬又浪费向量。
 * <p>用目录结构而不是"文件名排除列表"来解决：排除列表要维护，
 * 而目录边界一旦定下就自然生效。</p>
 *
 * <h3>为什么结果要排序</h3>
 * {@code classpath*} 通配扫描返回的资源顺序<b>不作保证</b>（取决于文件系统或 jar 内的条目顺序）。
 * 不排序的话，同一份语料在不同机器上切出的切片顺序可能不同，
 * 于是向量库里的 id 与内容对应关系会漂移，"可复现"就无从谈起。
 * 这里按文件路径排序，保证给定同一份语料必得同一份切片序列。</p>
 */
@Slf4j
@Component
public class KnowledgeCorpusLoader {

    /** 语料根目录（相对 classpath） */
    private static final String KNOWLEDGE_ROOT = "knowledge/";

    /** 参与切分的子目录。刻意用白名单而非递归扫描，理由见类注释 */
    private static final List<String> SCAN_DIRS = List.of("faq", "tickets");

    private final KnowledgeChunker chunker;

    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    public KnowledgeCorpusLoader(KnowledgeChunker chunker) {
        this.chunker = chunker;
    }

    /**
     * 加载全部语料并切分。
     *
     * @return 切片列表，按「文件路径 + 文档内序号」稳定排序
     * @throws UncheckedIOException 语料文件读取失败（属打包或环境故障，不应静默忽略）
     */
    public List<KnowledgeChunk> loadAll() {
        List<KnowledgeChunk> all = new ArrayList<>();
        int fileCount = 0;

        for (String dir : SCAN_DIRS) {
            String pattern = "classpath*:" + KNOWLEDGE_ROOT + dir + "/*.md";
            for (Resource resource : sortedResources(pattern)) {
                String fileName = String.valueOf(resource.getFilename());
                String sourceFile = dir + "/" + fileName;

                String content = readUtf8(resource, sourceFile);
                List<KnowledgeChunk> chunks = chunker.split(sourceFile, content);
                if (chunks.isEmpty()) {
                    log.warn("语料文件未产出切片，请检查是否缺少 ## 小节 | sourceFile={}", sourceFile);
                }
                all.addAll(chunks);
                fileCount++;
            }
        }

        log.info("知识库语料加载完成 | 文件数={} | 切片数={} | 平均每份文件切片数={}",
                fileCount, all.size(), fileCount == 0 ? 0 : (double) all.size() / fileCount);
        return all;
    }

    // ------------------------------------------------------------ 内部

    private Resource[] sortedResources(String pattern) {
        try {
            Resource[] resources = resourceResolver.getResources(pattern);
            Arrays.sort(resources, Comparator.comparing(resource -> String.valueOf(resource.getFilename())));
            return resources;
        } catch (IOException e) {
            throw new UncheckedIOException("扫描语料目录失败：" + pattern, e);
        }
    }

    private String readUtf8(Resource resource, String sourceFile) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取语料文件失败：" + sourceFile, e);
        }
    }

}
