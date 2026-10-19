package com.smartticket.rag.config;

import com.smartticket.rag.KnowledgeVectorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * 嵌入模型装配（阶段2 第5天）。
 *
 * <h3>为什么自己建一个 EmbeddingModel，而不用 Spring AI 的自动装配</h3>
 * 自动装配出的 {@code OpenAiEmbeddingModel} 会复用 {@code spring.ai.openai.*} 的 base-url 与密钥，
 * 也就是 <b>DeepSeek</b>——而 DeepSeek 没有 embeddings 端点（实测 {@code POST /v1/embeddings} 返回 404）。
 *
 * <p>根因是"一个厂商同时提供对话与嵌入"这个前提在本项目不成立：
 * 对话用 DeepSeek（已通），嵌入用阿里云百炼（DeepSeek 不提供）。两者是<b>不同的 base-url 与不同的密钥</b>，
 * 所以必须有两个独立的客户端，而不是共用一份配置。
 * （{@code application.yml} 里已把 {@code spring.ai.model.embedding} 设为 {@code none}，
 * 正是为了不让自动装配再产出一个指向 DeepSeek 的嵌入模型。）</p>
 *
 * <h3>复用容器的 RestClient.Builder 与 RetryTemplate</h3>
 * 两者都从容器取，而不是自己 new：
 * <ul>
 *   <li>{@link RestClient.Builder}：Spring Boot 的 {@code spring.http.client.*} 超时配置作用在它上面。
 *       自己 new 会绕过那两个超时，嵌入调用一旦卡住就会挂住工作线程——与 DEF-002 是同一类故障。</li>
 *   <li>{@link RetryTemplate}：由 {@code spring.ai.retry.max-attempts: 1} 配置，
 *       即"不重试"。自己 new 会拿到框架默认的 10 次重试，把一次失败拖成数分钟。</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(KnowledgeVectorProperties.class)
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel(KnowledgeVectorProperties properties,
                                         ObjectProvider<RestClient.Builder> restClientBuilderProvider,
                                         ObjectProvider<RetryTemplate> retryTemplateProvider) {
        KnowledgeVectorProperties.Embedding embedding = properties.getEmbedding();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(embedding.getBaseUrl())
                .apiKey(embedding.getApiKey())
                .restClientBuilder(restClientBuilderProvider.getIfAvailable(RestClient::builder))
                .build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(embedding.getModel())
                // 显式指定维度，不让服务端"按默认值来"：默认值变化会静默地让向量与表结构不匹配
                .dimensions(embedding.isSendDimensions() ? embedding.getDimensions() : null)
                .build();

        RetryTemplate retryTemplate = retryTemplateProvider.getIfAvailable(EmbeddingConfig::singleAttemptRetryTemplate);

        log.info("嵌入模型已装配 | protocol=openai-compatible | model={} | dimensions={} | baseUrl={}",
                embedding.getModel(), embedding.getDimensions(), embedding.getBaseUrl());

        // MetadataMode.NONE：只嵌入正文。切片的来源元数据（文件、小节标题）不进向量，
        // 否则同一段话会因为来源不同而得到不同向量，检索质量反而下降。
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.NONE, options, retryTemplate);
    }

    /**
     * 兜底用的"不重试"模板。
     *
     * <p>正常路径上容器里一定有 {@code RetryTemplate}（由 spring-ai-retry 自动配置提供，
     * 且已按 {@code spring.ai.retry.max-attempts} 设成 1 次）。这里只是不让本 bean
     * 在那种情况下装配失败。</p>
     */
    private static RetryTemplate singleAttemptRetryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(1));
        return retryTemplate;
    }

}
