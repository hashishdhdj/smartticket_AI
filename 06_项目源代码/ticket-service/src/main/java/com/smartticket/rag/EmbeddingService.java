package com.smartticket.rag;

import java.util.List;

/**
 * 文本向量化服务（阶段2 第5天，章程第4周任务 4）。
 *
 * <p>业务层只依赖本接口，不直接接触 Spring AI 的 {@code EmbeddingModel}：
 * 这样换厂商、换维度、换批量策略都不会溢出到检索逻辑里；也让"未配置/调用失败时降级"
 * 成为结构上的默认行为，而不是每个调用点都要记得写容错。</p>
 */
public interface EmbeddingService {

    /** 通道是否可用（已配置密钥且模型已装配）。不可用时 {@link #embed} 直接返回失败结果，不发网络请求 */
    boolean isAvailable();

    /** 实际生效的模型名，用于日志与库里的 {@code model} 列 */
    String modelName();

    /**
     * 批量向量化。
     *
     * <p>返回的向量与入参<b>顺序一一对应</b>——这一点是调用方拼装入库数据的前提，
     * 不能靠"服务端应该会保序"来假设，实现里会逐条校验数量。</p>
     *
     * @param texts 待向量化文本；为空或 {@code null} 时返回成功且结果为空列表（这不是错误）
     */
    EmbeddingResult embed(List<String> texts);

}
