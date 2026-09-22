package io.yunxi.platform.aistio.orchestration;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 预校验结果：累积全部问题，区分 HARD 与 WARN。
 */
@Data
public class ValidationResult {

    private List<ValidationIssue> issues = new ArrayList<>();

    public void add(ValidationIssue issue) {
        issues.add(issue);
    }

    /** 是否存在硬阻断问题（存在则不应导出 artifact） */
    public boolean hasHardErrors() {
        return issues.stream().anyMatch(i -> i.getSeverity() == ValidationSeverity.HARD);
    }

    /** 仅返回软告警问题 */
    public List<ValidationIssue> getWarnings() {
        return issues.stream()
                .filter(i -> i.getSeverity() == ValidationSeverity.WARN)
                .collect(Collectors.toList());
    }
}
