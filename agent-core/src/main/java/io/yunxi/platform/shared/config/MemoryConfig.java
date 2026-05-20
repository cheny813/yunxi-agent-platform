package io.yunxi.platform.shared.config;

import io.yunxi.platform.shared.constants.ConfigDefaults;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 记忆配置
 *
 * <p>
 * 封装智能记忆系统的所有配置参数。
 * </p>
 *
 * <p>
 * 记忆模式：
 * <ul>
 * <li>{@code none} - 无记忆，每次对话独立</li>
 * <li>{@code short_term} - 仅短期记忆（Redis 滑动窗口），会话内上下文连续</li>
 * <li>{@code full} - 短期 + 长期记忆（ReMe → Milvus），跨会话语义检索</li>
 * </ul>
 * </p>
 *
 * <p>
 * 此类位于 shared 层，可被所有层引用。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryConfig {

    /**
     * 记忆类型（如 "autocontext" 等）
     */
    private String type;

    /**
     * 最大 token 数
     */
    private Integer maxTokens;

    /**
     * 记忆模式
     * <p>
     * 可选值：
     * <ul>
     * <li>"none" - 无记忆模式</li>
     * <li>"short_term" - 仅短期记忆（Redis 滑动窗口）</li>
     * <li>"full" - 短期 + 长期记忆（ReMe → Milvus）（默认）</li>
     * </ul>
     * </p>
     */
    private String memoryMode = ConfigDefaults.DEFAULT_MEMORY_MODE;

    /**
     * 是否使用历史摘要
     */
    private Boolean useHistorySummary = true;

    /**
     * 记忆阈值（触发压缩的阈值）
     */
    private Integer memoryThreshold = ConfigDefaults.DEFAULT_MEMORY_THRESHOLD;

    /**
     * 最后保留消息数（始终保留的最近消息数）
     */
    private Integer lastKeepMessages = ConfigDefaults.DEFAULT_LAST_KEEP_MESSAGES;

    /**
     * Token 比例（压缩后 token 占原 token 的比例）
     */
    private Double tokenRatio = ConfigDefaults.DEFAULT_TOKEN_RATIO;

    /**
     * 最大历史消息数
     */
    private Integer maxHistory = ConfigDefaults.DEFAULT_MAX_HISTORY;

    /**
     * 最大上下文大小
     */
    private Integer maxContextSize = ConfigDefaults.DEFAULT_MAX_CONTEXT_SIZE;

    /**
     * 默认构造函数
     */
    public MemoryConfig() {
    }

    /**
     * 构造函数
     *
     * @param memoryMode 记忆模式
     */
    public MemoryConfig(String memoryMode) {
        this.memoryMode = memoryMode;
    }

    /**
     * 判断是否为无记忆模式
     */
    public boolean isNone() {
        return "none".equalsIgnoreCase(memoryMode);
    }

    /**
     * 判断是否为短期记忆模式（仅 Redis 滑动窗口）
     */
    public boolean isShortTerm() {
        return "short_term".equalsIgnoreCase(memoryMode);
    }

    /**
     * 判断是否为完整记忆模式（短期 + 长期 ReMe→Milvus）
     */
    public boolean isFullMode() {
        return "full".equalsIgnoreCase(memoryMode) || "smart".equalsIgnoreCase(memoryMode);
    }
}
