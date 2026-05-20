package io.yunxi.platform.framework.security;

/**
 * 命令安全级别
 *
 * <p>AI 生成的命令按危险程度分为四个级别：</p>
 * <ul>
 *   <li>{@link #SAFE} — 只读/查询命令，自动执行</li>
 *   <li>{@link #WARNING} — 有一定影响的操作，需用户确认</li>
 *   <li>{@link #DANGEROUS} — 高危操作，强制二次确认</li>
 *   <li>{@link #BLOCKED} — 禁止执行</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 */
public enum CommandSafety {

    /** 安全：只读/查询命令，自动执行 */
    SAFE("safe", "安全命令，自动执行"),

    /** 警告：有一定影响，需用户确认 */
    WARNING("warning", "需确认后执行"),

    /** 危险：高危操作，强制二次确认 */
    DANGEROUS("dangerous", "高危操作，需二次确认"),

    /** 阻止：禁止执行 */
    BLOCKED("blocked", "禁止执行");

    /** 安全级别编码 */
    private final String code;
    /** 安全级别描述 */
    private final String description;

    /**
     * 构造函数
     *
     * @param code        安全级别编码
     * @param description 安全级别描述
     */
    CommandSafety(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 获取安全级别编码
     *
     * @return 安全级别编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取安全级别描述
     *
     * @return 安全级别描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 是否需要确认
     */
    public boolean requiresConfirmation() {
        return this == WARNING || this == DANGEROUS;
    }
}
