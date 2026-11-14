package io.yunxi.runtime.rag;

/** 文本嵌入客户端抽象：将文本映射为稠密向量。 */
public interface EmbeddingClient {

    /** 对单段文本做嵌入，返回与配置维度一致的稠密向量。 */
    float[] embed(String text);
}
