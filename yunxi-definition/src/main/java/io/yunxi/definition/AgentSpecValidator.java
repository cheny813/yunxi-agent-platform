package io.yunxi.definition;

/**
 * 在校验 {@link AgentSpec} 交给运行时平面装配前，先对其进行校验。
 */
public interface AgentSpecValidator {
    /** 校验 AgentSpec 结构合法；返回含错误明细的 {@link AgentSpec.ValidationResult}。 */
    AgentSpec.ValidationResult validate(AgentSpec spec);
}
