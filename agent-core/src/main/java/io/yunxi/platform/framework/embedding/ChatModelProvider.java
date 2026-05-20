package io.yunxi.platform.framework.embedding;

import io.agentscope.core.model.Model;

/**
 * 聊天模型提供商接口
 * <p>
 * 统一不同模型提供商（OpenAI、DashScope、Claude 等）的调用接口
 * 直接使用 AgentScope 的 Model 接口，便于与 ReActAgent 集成
 * </p>
 */
public interface ChatModelProvider extends Model {

    /**
     * 获取提供商名称
     *
     * @return 提供商名称（openai、dashscope、claude 等）
     */
    String getProvider();

    /**
     * 验证配置是否有效
     *
     * @return true 如果配置有效
     */
    boolean isValid();
}
