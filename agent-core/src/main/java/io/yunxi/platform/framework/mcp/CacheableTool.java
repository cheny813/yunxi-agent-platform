package io.yunxi.platform.framework.mcp;

import java.lang.annotation.*;

/**
 * MCP 工具结果缓存注解
 *
 * <p>标记在工具方法上，启用结果缓存。缓存 key 由工具名称和参数自动生成。</p>
 *
 * <h3>使用示例</h3>
 * <pre>
 * &#64;CacheableTool(expireSeconds = 300, keyGenerator = ToolCacheKeyGenerator.class)
 * public Object queryDatabase(String sql) {
 *     // ...
 * }
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CacheableTool {

    /**
     * 缓存过期时间（秒）
     * 默认 5 分钟
     */
    int expireSeconds() default 300;

    /**
     * 缓存 key 生成策略类
     */
    Class<? extends ToolCacheKeyGenerator> keyGenerator() default DefaultToolCacheKeyGenerator.class;

    /**
     * 是否启用缓存
     * 可用于动态控制
     */
    boolean enabled() default true;

    /**
     * 缓存前缀
     */
    String prefix() default "mcp:tool:";
}