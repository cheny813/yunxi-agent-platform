package io.yunxi.platform.aistio.orchestration;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 单条预校验问题：级别、规则编码、关联节点 key、可读说明。
 */
@Data
@AllArgsConstructor
public class ValidationIssue {

    private ValidationSeverity severity;

    /** 规则编码，对应 OrchestrationTranslator 中的规则常量（如 NODE_KEY_DUP / CEL_NOT_COMPILABLE） */
    private String ruleCode;

    /** 关联节点 key，全局性问题可为空 */
    private String nodeKey;

    /** 可读说明 */
    private String message;
}
