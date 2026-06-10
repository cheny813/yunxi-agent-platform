package io.yunxi.platform.agent;

import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.yunxi.platform.embedding.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 将项目 EmbeddingService 适配为 V2.0 AgentScope EmbeddingModel 接口。
 *
 * <p>
 * 替代 V1.1 中使用动态代理的 AgentscopeEmbeddingModelAdapter，直接实现接口。
 * V2.0 的 EmbeddingModel 要求：
 * </p>
 * <ul>
 * <li>{@link #embed(ContentBlock)} — 对 TextBlock 类型生成向量</li>
 * <li>{@link #getDimensions()} — 返回嵌入向量维度</li>
 * <li>{@link #getModelName()} — 返回模型标识名称</li>
 * </ul>
 *
 * <p>
 * 仅支持 TextBlock 类型的内容块，其他类型抛出 {@link EmbeddingException}。
 * </p>
 *
 * @see io.agentscope.core.embedding.EmbeddingModel
 * @see EmbeddingService
 */
public class YunxiEmbeddingModel implements EmbeddingModel {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(YunxiEmbeddingModel.class);

    /** 底层嵌入服务，提供实际的向量生成能力 */
    private final EmbeddingService embeddingService;

    /**
     * 模型标识名称
     * <p>
     * 格式为 "yunxi-embedding-{provider}"，如 "yunxi-embedding-baidu"
     * </p>
     */
    private final String modelName;

    /**
     * 创建 Yunxi 嵌入模型适配器。
     *
     * @param embeddingService 底层嵌入服务实例
     */
    public YunxiEmbeddingModel(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
        this.modelName = "yunxi-embedding-" + embeddingService.getProviderName();
    }

    /**
     * 对内容块生成嵌入向量。
     *
     * <p>
     * 仅支持 {@link TextBlock} 类型，将文本传入底层 EmbeddingService 生成向量。
     * 其他类型的内容块（如 ImageBlock）抛出 {@link EmbeddingException}。
     * </p>
     *
     * <p>
     * Float → double 转换：底层返回 List&lt;Float&gt;，V2.0 接口要求 double[]，
     * 通过流式映射进行类型转换。
     * </p>
     *
     * @param block 内容块，仅支持 TextBlock
     * @return Mono 包装的嵌入向量数组
     * @throws EmbeddingException 内容块类型不支持时抛出
     */
    @Override
    public Mono<double[]> embed(ContentBlock block) {
        if (block instanceof TextBlock textBlock) {
            return Mono.fromCallable(() -> {
                // 调用底层嵌入服务生成向量
                List<Float> result = embeddingService.embed(textBlock.getText());
                // Float → double 类型转换
                return result.stream().mapToDouble(Float::doubleValue).toArray();
            });
        }
        // 不支持的内容块类型
        return Mono.error(new EmbeddingException(
                "Unsupported content block type: " + block.getClass().getSimpleName()));
    }

    /**
     * 获取模型标识名称。
     *
     * @return 模型名称，格式为 "yunxi-embedding-{provider}"
     */
    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 获取嵌入向量维度。
     *
     * @return 向量维度数，由底层 EmbeddingService 决定
     */
    @Override
    public int getDimensions() {
        return embeddingService.getDimension();
    }
}
