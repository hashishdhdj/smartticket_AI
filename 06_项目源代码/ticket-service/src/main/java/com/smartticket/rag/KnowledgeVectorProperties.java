package com.smartticket.rag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 向量库配置（阶段2 第5天），前缀 {@code smartticket.vector}。
 *
 * <p>刻意放在 {@code smartticket.vector} 下而不是 {@code spring.*}：这些是<b>项目自己的</b>配置项，
 * 与框架配置分区管理，便于"一键关掉 RAG"而不动框架（与 {@code smartticket.ai.enabled} 同一思路）。</p>
 *
 * <p>用 {@link Getter}/{@link Setter} 而不是 {@code @Data}：{@code @Data} 会生成 {@code toString()}，
 * 把密码带进任何一次日志打印。这里宁可少一个方法，也不给"密钥出现在日志里"留机会。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "smartticket.vector")
public class KnowledgeVectorProperties {

    /** 启动时是否自动建立/刷新索引；默认关闭，理由见 application.yml 注释 */
    private boolean indexOnStartup = false;

    private Datasource datasource = new Datasource();

    private Embedding embedding = new Embedding();

    private Retrieval retrieval = new Retrieval();

    private Fusion fusion = new Fusion();

    /**
     * 生成放行阈值，2026-10-16按92道已知题的召回优先F2重新校准为0.63。
     * 正例放行65/66，负例放行1/26；相似度不是事实正确概率。
     * 另30道已知措辞回归仍有漏答及负例放行，保留模型语义拒答、来源和权限检查。
     * 低于阈值不调用模型；达到阈值也不代表答案一定有依据。
     * 详见阶段测试报告中的RAG当前门槛策略.json；更换模型或语料后需重新校准。
     */
    private double confidenceThreshold = 0.63;

    /** 双路融合参数（阶段2 第7天，章程第4周任务 8） */
    @Getter
    @Setter
    public static class Fusion {

        /**
         * 融合后保留几条。
         *
         * <p>取 3 的依据：章程给的 RAG 返回结构里 {@code sourceDocuments} 是一个数组，
         * 要给模型 3 条依据足够支撑一次回答；给太多会把不相关的内容也塞进 Prompt，
         * 反而稀释重点（第4天语料决策里"噪声稀释 Prompt"是同一个理由）。</p>
         *
         * <p>每路先各自取 {@code retrieval.top-k}（默认 5）条再融合，
         * 所以"能进最终结果"的候选池比 3 大，融合才有得挑。</p>
         */
        private int topN = 3;

        // 说明：RRF 的平滑常数 k 没有做成配置项，见 RrfFusion 类注释——
        // 没有评估集之前调它没有依据，做成配置只会诱导随手改数。

    }

    /** 检索参数（阶段2 第6天，章程第4周任务 6/7） */
    @Getter
    @Setter
    public static class Retrieval {

        /**
         * 每条路各取前多少条。
         *
         * <p>取 5 的依据：语料总共 26 条切片，融合后还要取前 3 条给出依据（章程的返回结构里
         * {@code sourceDocuments} 是数组）。每路 5 条既给了合并层足够的挑选余量，
         * 又不会把明显不相关的内容一起带进来。</p>
         */
        private int topK = 5;

        /**
         * 关键词检索的最低相似度门槛，低于它的切片不进入结果。
         *
         * <p>取 0.2 是<b>按实测的分数分层标定</b>出来的，不是拍脑袋（第7天）：</p>
         *
         * <pre>
         * 0.36        查询词在正文中连续出现（真词命中）           → 保留
         * 0.18        多个实词零散命中                            → 挡掉
         * 0.09 ~ 0.14 只命中"的/怎/么/办"这类虚词（与问题无关）   → 挡掉
         * </pre>
         *
         * <p>实测效果：查询「我的账号登不进去了」从 3 条无关结果降到 0 条，
         * 而「重复扣款如何申请退款」精确命中的那条（0.3636）不受影响。</p>
         *
         * <p>取值低到 0 时，那些"与问题无关但字符有三元组重合"的切片会以 rank 1 的身份
         * 被融合层当作高优先候选引入，把最终结果整体带偏——这是第6天实测确认的问题。</p>
         *
         * <p><b>局限</b>：该值基于当前 26 条演示语料标定。语料规模或措辞大改后应重新标定
         * （第5周有评估集后可以算不同门槛下的召回指标，那时再定更可靠）。</p>
         */
        private double keywordMinScore = 0.2;

    }

    /** PostgreSQL（pgvector）连接参数。这是应用里的<b>第二个</b>数据源 */
    @Getter
    @Setter
    public static class Datasource {

        private String url = "jdbc:postgresql://127.0.0.1:5432/smartticket_vector";

        private String username = "postgres";

        /** 真实值在 application-local.yml（已 gitignore），仓库里的占位为空串 */
        private String password = "";

        /** 连接获取超时（毫秒）。与 MySQL 同理由：库不可用时快速失败，不挂住工作线程 */
        private int connectionTimeoutMs = 3000;

        /**
         * 连接池上限。
         *
         * <p>取 4 而不是与 MySQL 相当的规模：向量库的调用只发生在"建索引"和"检索"两处，
         * 频率远低于业务库，池子开大只是白白占着 PostgreSQL 的连接数。</p>
         */
        private int maximumPoolSize = 4;

    }

    /** 嵌入模型配置。走 OpenAI 兼容协议，换厂商只改 base-url + model */
    @Getter
    @Setter
    public static class Embedding {

        /**
         * 嵌入服务地址。
         *
         * <p><b>必须是"不带 {@code /v1} 的主机根地址"</b>：Spring AI 的 {@code OpenAiApi.Builder}
         * 把 {@code /v1/embeddings} 写成了默认路径，并会把它拼在 base-url 之后。
         * 若把厂商文档里的 {@code .../compatible-mode/v1} 原样填进来，
         * 实际请求会变成 {@code .../compatible-mode/v1/v1/embeddings} 而返回 404（已实测）。
         * 这与对话侧的约定一致——{@code spring.ai.openai.base-url} 填的也是不带 {@code /v1} 的根地址。</p>
         */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";

        /** 占位值 "not-configured" 表示未配置；与 chat 的占位约定保持一致 */
        private String apiKey = "not-configured";

        private String model = "text-embedding-v4";

        /**
         * 向量维度。必须与 {@code sql-vector/01_init_pgvector.sql} 里 {@code VECTOR(1024)} 一致。
         *
         * <p>程序会拿它校验每次返回的向量长度：长度不符说明配置与表结构脱钩，
         * 此时写入必然失败，早一点报出来比让 PostgreSQL 抛类型错误更好定位。</p>
         */
        private int dimensions = 1024;

        /**
         * 单次请求最多送几条文本。
         *
         * <p>取 10 是服务方的硬上限（一次最多 10 行），不是性能调优值。写进配置而不是常量，
         * 是为了换厂商时能在配置里改，不必动代码。</p>
         */
        private int batchSize = 10;

        /** False for fixed-dimensional APIs such as BGE-M3; output is still validated against dimensions. */
        private boolean sendDimensions = true;

    }

}
