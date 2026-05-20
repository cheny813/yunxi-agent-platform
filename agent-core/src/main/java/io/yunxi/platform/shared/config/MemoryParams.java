package io.yunxi.platform.shared.config;

/**
 * 记忆参数接口
 *
 * <p>任何需要使用智能记忆功能的请求对象都应该实现此接口。</p>
 *
 * <p>此接口位于 shared 层，可被所有层引用。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface MemoryParams {

    /**
     * 获取记忆配置
     *
     * @return 记忆配置对象
     */
    MemoryConfig getMemoryConfig();

    /**
     * 设置记忆配置
     *
     * @param config 记忆配置对象
     */
    void setMemoryConfig(MemoryConfig config);
}
