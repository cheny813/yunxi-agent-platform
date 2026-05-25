package io.yunxi.platform.shared.config;

import java.util.Map;

/**
 * 扩展点配置 - 业务自定义逻辑的 Spring Bean 引用
 * <p>
 * 15% 的场景需要通过扩展点插入自定义逻辑。
 * 配置值为 Spring Bean 名称，框架自动注入。
 * </p>
 *
 * <pre>
 * 配置示例：
 * extensions:
 *   preProcessor: "nutritionPreProcessor"
 *   postProcessor: "auditPostProcessor"
 * </pre>
 *
 * @author yunxi-platform
 */
public class ExtensionConfig {

    /** Agent 调用前置处理器 Bean 名称 */
    private String preProcessor;

    /** Agent 调用后置处理器 Bean 名称 */
    private String postProcessor;

    /** Agent 自定义装配器 Bean 名称（5% 场景） */
    private String agentCustomizer;

    /** 扩展参数（传递给扩展点的自由配置） */
    private Map<String, Object> params;

    /** HITL (Human-in-the-Loop) 配置 */
    private HITLConfig hitl;

    public ExtensionConfig() {
    }

    public String getPreProcessor() {
        return preProcessor;
    }

    public void setPreProcessor(String preProcessor) {
        this.preProcessor = preProcessor;
    }

    public String getPostProcessor() {
        return postProcessor;
    }

    public void setPostProcessor(String postProcessor) {
        this.postProcessor = postProcessor;
    }

    public String getAgentCustomizer() {
        return agentCustomizer;
    }

    public void setAgentCustomizer(String agentCustomizer) {
        this.agentCustomizer = agentCustomizer;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public HITLConfig getHitl() {
        return hitl;
    }

    public void setHitl(HITLConfig hitl) {
        this.hitl = hitl;
    }
}
