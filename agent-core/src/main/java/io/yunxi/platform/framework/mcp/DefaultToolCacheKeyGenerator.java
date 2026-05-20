package io.yunxi.platform.framework.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * 默认 MCP 工具缓存 Key 生成器
 *
 * <p>使用工具名称 + 参数的 JSON 序列化结果作为缓存 key，并进行 MD5 哈希。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
public class DefaultToolCacheKeyGenerator implements ToolCacheKeyGenerator {

    /** JSON 序列化工具 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 生成缓存 key
     *
     * @param serverName 服务器名称
     * @param toolName   工具名称
     * @param arguments  工具参数
     * @return MD5 哈希后的缓存 key
     */
    @Override
    public String generate(String serverName, String toolName, Map<String, Object> arguments) {
        try {
            // 使用 TreeMap 保证参数顺序一致
            Map<String, Object> normalizedArgs = arguments != null
                    ? new TreeMap<>(arguments)
                    : Map.of();

            String rawKey = serverName + ":" + toolName + ":" + MAPPER.writeValueAsString(normalizedArgs);

            // MD5 哈希，生成 32 位字符串
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (Exception e) {
            // 降级方案：直接拼接
            return serverName + ":" + toolName + ":" + arguments.hashCode();
        }
    }
}