package io.yunxi.platform.aistio.orchestration;

import java.util.List;

/**
 * 预校验硬阻断异常：导出阶段遇到 HARD 级问题时抛出，阻断 artifact 生成。
 */
public class OrchestrationSpecValidationException extends RuntimeException {

    private final transient List<ValidationIssue> hardErrors;

    public OrchestrationSpecValidationException(String message, List<ValidationIssue> hardErrors) {
        super(message);
        this.hardErrors = hardErrors;
    }

    public List<ValidationIssue> getHardErrors() {
        return hardErrors;
    }
}
