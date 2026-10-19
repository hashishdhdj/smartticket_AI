package com.smartticket.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时建立/刷新知识库索引（阶段2 第5天）。
 *
 * <p>默认<b>不执行</b>，需显式打开：
 * {@code --smartticket.vector.index-on-startup=true}。理由见 application.yml 注释——
 * 这个动作会调用按量计费的外部接口并依赖 PostgreSQL，不该成为应用启动的必要条件。</p>
 *
 * <p>单独一个类而不是让 {@code KnowledgeIndexingService} 直接实现 {@link ApplicationRunner}：
 * 后者会让"索引能力"与"启动时自动执行"绑死，无法在测试或将来加接口触发时复用同一个服务而不触发自动执行。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "smartticket.vector", name = "index-on-startup", havingValue = "true")
public class KnowledgeIndexingRunner implements ApplicationRunner {

    private final KnowledgeIndexingService indexingService;

    public KnowledgeIndexingRunner(KnowledgeIndexingService indexingService) {
        this.indexingService = indexingService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始建立知识库索引（由 smartticket.vector.index-on-startup=true 触发）");
        KnowledgeIndexingService.IndexingReport report = indexingService.indexAll();

        if (report.success()) {
            log.info("知识库索引结束 | 切片={} | 跳过={} | 写入={} | cost={}ms",
                    report.totalChunks(), report.skipped(), report.written(), report.costMs());
        } else {
            // 不抛异常：RAG 是可选增强，索引失败不应让应用启动失败
            log.warn("知识库索引未完成，RAG 检索将返回未命中 | errorCode={} | detail={} | cost={}ms",
                    report.errorCode(), report.failureDetail(), report.costMs());
        }
    }

}
