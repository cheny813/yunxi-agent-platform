package io.yunxi.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * {@link SpecCodec} 的 YAML 实现。使用 Jackson 的 YAML 工厂；AgentSpec 记录被原样序列化，
 * 因此磁盘形态与契约形态一致（两者不会出现漂移）。
 */
public class YamlSpecCodec implements SpecCodec {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    /** 把 AgentSpec 序列化为 YAML 文本；失败包装为 {@link IllegalArgumentException}。 */
    @Override
    public String encode(AgentSpec spec) {
        try {
            return mapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to encode AgentSpec", e);
        }
    }

    /** 把 YAML 文本反序列化为 AgentSpec；失败包装为 {@link IllegalArgumentException}。 */
    @Override
    public AgentSpec decode(String content) {
        try {
            return mapper.readValue(content, AgentSpec.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to decode AgentSpec", e);
        }
    }

    /** 返回媒体类型 {@code application/x-yaml}。 */
    @Override
    public String mediaType() {
        return "application/x-yaml";
    }
}
