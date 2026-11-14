package io.yunxi.definition;

/**
 * 负责 {@link AgentSpec} 的序列化与反序列化编解码器。编码解码器是唯一知晓磁盘线格式的地方；
 * 运行时平面从不直接解析定义文本。
 */
public interface SpecCodec {
    /** 把 AgentSpec 编码为线格式字符串。 */
    String encode(AgentSpec spec);

    /** 把线格式字符串解码为 AgentSpec；格式非法时抛异常。 */
    AgentSpec decode(String content);

    /** 返回本编解码器对应的媒体类型（如 application/x-yaml）。 */
    String mediaType();
}
