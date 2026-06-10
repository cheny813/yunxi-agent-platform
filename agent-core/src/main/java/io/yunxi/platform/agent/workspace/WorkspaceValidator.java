package io.yunxi.platform.agent.workspace;

import io.yunxi.platform.agent.workspace.model.WorkspaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区校验器
 *
 * <p>独立验证指定工作区的结构完整性，在启动时或通过 CLI 工具调用。
 * 检查 AGENTS.md、目录结构、必需文件是否存在。</p>
 *
 * @author yunxi-agent-platform
 */
public class WorkspaceValidator {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceValidator.class);

    /** 校验结果 */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors;
            this.warnings = warnings;
        }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
    }

    public ValidationResult validate(String workspacePath) {
        return validate(Path.of(workspacePath));
    }

    public ValidationResult validate(Path workspacePath) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!Files.exists(workspacePath)) {
            errors.add("工作区目录不存在: " + workspacePath);
            return new ValidationResult(false, errors, warnings);
        }

        Path agentsMd = workspacePath.resolve("AGENTS.md");
        if (!Files.exists(agentsMd)) {
            errors.add("缺少 AGENTS.md — Agent 身份描述文件（必需）");
        } else if (!isReadable(agentsMd)) {
            errors.add("AGENTS.md 文件不可读");
        }

        Path knowledgeDir = workspacePath.resolve("knowledge");
        if (!Files.exists(knowledgeDir)) {
            errors.add("缺少 knowledge/ 目录（必需）");
        } else if (!Files.isDirectory(knowledgeDir)) {
            errors.add("knowledge 不是目录");
        }

        Path skillsDir = workspacePath.resolve("skills");
        if (!Files.exists(skillsDir)) {
            warnings.add("缺少 skills/ 目录（可选，建议创建）");
        }

        Path subAgentsDir = workspacePath.resolve("subagents");
        if (!Files.exists(subAgentsDir)) {
            warnings.add("缺少 subagents/ 目录（可选，需要子智能体时创建）");
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    public List<ValidationResult> validateAll(List<WorkspaceConfig> configs) {
        List<ValidationResult> results = new ArrayList<>();
        for (WorkspaceConfig config : configs) {
            results.add(validate(config.getWorkspacePath()));
        }
        return results;
    }

    public void printReport(String agentName, ValidationResult result) {
        if (result.isValid()) {
            log.info("工作区校验通过: agent={}", agentName);
        } else {
            log.warn("工作区校验失败: agent={}", agentName);
            for (String error : result.getErrors()) {
                log.warn("  [ERROR] {}", error);
            }
        }
        for (String warning : result.getWarnings()) {
            log.info("  [WARN] {}", warning);
        }
    }

    private boolean isReadable(Path path) {
        try { return Files.size(path) > 0; }
        catch (Exception e) { return false; }
    }
}
