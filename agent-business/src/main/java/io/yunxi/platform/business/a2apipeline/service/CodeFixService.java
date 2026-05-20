package io.yunxi.platform.business.a2apipeline.service;

import io.yunxi.platform.business.a2apipeline.model.*;
import io.yunxi.platform.shared.dto.StageResult;
import io.yunxi.platform.framework.intelligent.llm.IntelligentLlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 代码修复服务
 * <p>
 * Pipeline Stage 2: 基于 LLM 生成代码修复方案。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class CodeFixService implements PipelineStage {

    /** 智能大模型服务，用于 LLM 驱动的代码修复 */
    @Autowired
    private IntelligentLlmService llmService;

    /**
     * 获取阶段名称
     *
     * @return 阶段名称 "CODE_FIX"
     */
    @Override
    public String getName() {
        return "CODE_FIX";
    }

    /**
     * 判断修复阶段是否可执行，需要代码分析阶段已完成
     *
     * @param previousResults 前序阶段的执行结果
     * @return 代码分析阶段完成时返回 true，否则返回 false
     */
    @Override
    public boolean canExecute(Map<String, StageResult> previousResults) {
        StageResult analysisResult = previousResults.get("CODE_ANALYSIS");
        return analysisResult != null
                && analysisResult.getStatus() == StageResult.StageStatus.COMPLETED;
    }

    /**
     * 执行修复阶段（接口方法实现），提取分析结果后委托给 fix 方法
     *
     * @param context         流水线上下文
     * @param previousResults 前序阶段的执行结果
     * @return 阶段执行结果
     */
    @Override
    public StageResult execute(PipelineContext context, Map<String, StageResult> previousResults) {
        StageResult analysisResult = previousResults.get("CODE_ANALYSIS");
        return fix(context, analysisResult);
    }

    /**
     * 验证修复结果是否有效
     *
     * @param result 阶段执行结果
     * @return 结果非空、阶段名称为 CODE_FIX 且状态不为 null 时返回 true
     */
    @Override
    public boolean validateResult(StageResult result) {
        return result != null
                && "CODE_FIX".equals(result.getStageName())
                && result.getStatus() != null;
    }

    /**
     * 执行代码修复
     *
     * @param context        流水线上下文
     * @param analysisResult 分析阶段结果
     * @return 阶段结果（包含修复代码）
     */
    public StageResult fix(PipelineContext context, StageResult analysisResult) {
        long startTime = System.currentTimeMillis();
        log.info("[CODE_FIX] 开始修复: instanceId={}", context.getInstanceId());

        try {
            CodeFixRequest request = context.getRequest();

            String fixPrompt = buildFixPrompt(request, analysisResult);

            String fixedCode = llmService.generateOrDefault(
                    "你是一个代码修复专家，负责根据问题分析生成修复代码。" +
                            "请只输出修复后的代码，不要解释。",
                    fixPrompt, "");

            boolean valid = validateFix(fixedCode);

            if (!valid) {
                log.warn("[CODE_FIX] 修复代码验证失败: instanceId={}", context.getInstanceId());
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("[CODE_FIX] 修复完成: instanceId={}, valid={}, duration={}ms",
                    context.getInstanceId(), valid, duration);

            return StageResult.builder()
                    .stageName("CODE_FIX")
                    .status(valid ? StageResult.StageStatus.COMPLETED : StageResult.StageStatus.FAILED)
                    .startTimeMs(startTime)
                    .endTimeMs(System.currentTimeMillis())
                    .durationMs(duration)
                    .findings(analysisResult != null ? analysisResult.getFindings() : null)
                    .output(Map.of(
                            "fixedCode", fixedCode != null ? fixedCode : "",
                            "validated", valid,
                            "codeLength", fixedCode != null ? fixedCode.length() : 0))
                    .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[CODE_FIX] 修复失败: instanceId={}", context.getInstanceId(), e);

            return StageResult.builder()
                    .stageName("CODE_FIX")
                    .status(StageResult.StageStatus.FAILED)
                    .startTimeMs(startTime)
                    .endTimeMs(System.currentTimeMillis())
                    .durationMs(duration)
                    .output(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * 构建代码修复提示词，包含问题描述、错误日志、堆栈跟踪和分析发现
     *
     * @param request        代码修复请求
     * @param analysisResult 分析阶段结果
     * @return 构建好的提示词字符串
     */
    private String buildFixPrompt(CodeFixRequest request, StageResult analysisResult) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("## 问题描述\n");
        prompt.append(request.getDescription()).append("\n\n");

        if (request.getContext() != null) {
            if (request.getContext().getErrorLog() != null) {
                prompt.append("## 错误日志\n");
                prompt.append(request.getContext().getErrorLog()).append("\n\n");
            }
            if (request.getContext().getStackTrace() != null) {
                prompt.append("## 堆栈跟踪\n");
                prompt.append(request.getContext().getStackTrace()).append("\n\n");
            }
        }

        if (analysisResult != null && analysisResult.getFindings() != null
                && !analysisResult.getFindings().isEmpty()) {
            prompt.append("## 分析发现的问题\n");
            for (StageResult.Finding finding : analysisResult.getFindings()) {
                prompt.append("- [").append(finding.getSeverity()).append("] ");
                prompt.append(finding.getTitle());
                if (finding.getFilePath() != null && !finding.getFilePath().isEmpty()) {
                    prompt.append(" (").append(finding.getFilePath());
                    if (finding.getLineNumber() > 0) {
                        prompt.append(":").append(finding.getLineNumber());
                    }
                    prompt.append(")");
                }
                prompt.append("\n  ").append(finding.getDescription()).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("请生成修复代码，确保：\n");
        prompt.append("1. 修复所有识别的问题\n");
        prompt.append("2. 不引入新的问题\n");
        prompt.append("3. 保持代码风格一致\n");
        prompt.append("4. 添加必要的注释说明修复原因\n");

        return prompt.toString();
    }

    /**
     * 验证修复后的代码是否有效（非空、长度合理、括号匹配）
     *
     * @param fixedCode 修复后的代码
     * @return 代码验证通过返回 true，否则返回 false
     */
    private boolean validateFix(String fixedCode) {
        if (fixedCode == null || fixedCode.isBlank()) {
            return false;
        }
        if (fixedCode.length() < 10) {
            return false;
        }
        long openBraces = fixedCode.chars().filter(c -> c == '{').count();
        long closeBraces = fixedCode.chars().filter(c -> c == '}').count();
        if (openBraces != closeBraces) {
            log.warn("[CODE_FIX] 括号不匹配: open={}, close={}", openBraces, closeBraces);
            return false;
        }
        return true;
    }
}
