package io.yunxi.platform.framework.skill;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * SDK 技能适配器接口
 *
 * <p>
 * 用于将外部技能转换为平台可调用的工具格式
 * </p>
 *
 * @author yunxi-agent-platform
 */
public interface SkillAdapter {

    /**
     * 获取技能名称
     *
     * @return 技能名称
     */
    String getName();

    /**
     * 获取技能描述
     *
     * @return 技能描述
     */
    String getDescription();

    /**
     * 获取技能参数定义
     *
     * @return 参数定义schema
     */
    Map<String, Object> getParameters();

    /**
     * 执行技能
     *
     * @param params 技能参数
     * @return 执行结果
     */
    Mono<SkillExecutionResult> execute(Map<String, Object> params);

    /**
     * 检查技能是否可用
     *
     * @return 是否可用
     */
    Mono<Boolean> isAvailable();
}