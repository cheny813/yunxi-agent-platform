package io.yunxi.platform.business.a2apipeline.service;

import io.yunxi.platform.business.a2apipeline.model.*;
import io.yunxi.platform.shared.dto.StageResult;
import io.yunxi.platform.framework.intelligent.llm.IntelligentLlmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 代码审查服务
 * <p>
 * Pipeline Stage 3: 对修复后的代码进行审查。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class CodeReviewService implements PipelineStage {

    /** 智能大模型服务，用于 LLM 驱动的代码审查 */
    @Autowired
    private IntelligentLlmService llmService;

    /**
     * 获取阶段名称
     *
     * @return 阶段名称 "CODE_REVIEW"
     */
    @Override
    public String getName() {
        return "CODE_REVIEW";
    }

    /**
     * 判断审查阶段是否可执行，需要代码修复阶段已完成
     *
     * @param previousResults 前序阶段的执行结果
     * @return 代码修复阶段完成时返回 true，否则返回 false
     */
    @Override
    public boolean canExecute(Map<String, StageResult> previousResults) {
        StageResult fixResult = previousResults.get("CODE_FIX");
        return fixResult != null
                && fixResult.getStatus() == StageResult.StageStatus.COMPLETED;
    }

    /**
     * 执行审查阶段（接口方法实现），提取修复结果后委托给 review 方法
     *
     * @param context         流水线上下文
     * @param previousResults 前序阶段的执行结果
     * @return 阶段执行结果
     */
    @Override
    public StageResult execute(PipelineContext context, Map<String, StageResult> previousResults) {
        StageResult fixResult = previousResults.get("CODE_FIX");
        return review(context, fixResult);
    }

    /**
     * 验证审查结果是否有效
     *
     * @param result 阶段执行结果
     * @return 结果非空、阶段名称为 CODE_REVIEW 且状态不为 null 时返回 true
     */
    @Override
    public boolean validateResult(StageResult result) {
        return result != null
                && "CODE_REVIEW".equals(result.getStageName())
                && result.getStatus() != null;
    }

    /**
     * 执行代码审查
     *
     * @param context   流水线上下文
     * @param fixResult 修复阶段结果
     * @return 阶段结果（包含审查发现和评分）
     */
    public StageResult review(PipelineContext context, StageResult fixResult) {
        long startTime = System.currentTimeMillis();
        log.info("[CODE_REVIEW] 开始审查: instanceId={}", context.getInstanceId());

        try {
            List<StageResult.Finding> findings = new ArrayList<>();

            String fixedCode = fixResult != null && fixResult.getOutput() != null
                    ? (String) fixResult.getOutput().getOrDefault("fixedCode", "")
                    : "";

            findings.addAll(performStaticChecks(fixedCode));

            ReviewScore llmReview = reviewWithLlm(context.getRequest(), fixedCode);
            if (llmReview != null) {
                findings.addAll(llmReview.findings);
            }

            double score = calculateReviewScore(findings);
            boolean approved = score >= 60.0;

            long duration = System.currentTimeMillis() - startTime;
            log.info("[CODE_REVIEW] 审查完成: instanceId={}, score={}, approved={}, findings={}, duration={}ms",
                    context.getInstanceId(), score, approved, findings.size(), duration);

            return StageResult.builder()
                    .stageName("CODE_REVIEW")
                    .status(approved ? StageResult.StageStatus.COMPLETED : StageResult.StageStatus.FAILED)
                    .startTimeMs(startTime)
                    .endTimeMs(System.currentTimeMillis())
                    .durationMs(duration)
                    .findings(findings)
                    .output(Map.of(
                            "score", score,
                            "approved", approved,
                            "findingCount", findings.size()))
                    .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[CODE_REVIEW] 审查失败: instanceId={}", context.getInstanceId(), e);

            return StageResult.builder()
                    .stageName("CODE_REVIEW")
                    .status(StageResult.StageStatus.FAILED)
                    .startTimeMs(startTime)
                    .endTimeMs(System.currentTimeMillis())
                    .durationMs(duration)
                    .output(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * 对代码执行静态检查，检测硬编码密码、SQL注入、空异常捕获等问题
     *
     * @param code 待检查的代码
     * @return 发现的问题列表
     */
    private List<StageResult.Finding> performStaticChecks(String code) {
        List<StageResult.Finding> findings = new ArrayList<>();

        if (code == null || code.isBlank()) {
            return findings;
        }

        if (code.contains("password") && code.matches("(?i).*password\\s*=\\s*\"[^\"]+\".*")) {
            findings.add(StageResult.Finding.builder()
                    .title("安全风险：硬编码密码")
                    .severity("HIGH")
                    .description("检测到硬编码密码，应使用环境变量或配置管理")
                    .build());
        }

        if (code.contains("String.format") && code.contains("SELECT")
                || code.contains("executeQuery(\"SELECT") && code.contains("+")) {
            findings.add(StageResult.Finding.builder()
                    .title("安全风险：潜在 SQL 注入")
                    .severity("HIGH")
                    .description("检测到字符串拼接 SQL，应使用参数化查询")
                    .build());
        }

        if (code.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*")) {
            findings.add(StageResult.Finding.builder()
                    .title("代码质量：空异常捕获")
                    .severity("MEDIUM")
                    .description("检测到空的 catch 块，应至少记录日志")
                    .build());
        }

        if (code.contains("System.out.println") || code.contains("System.err.println")) {
            findings.add(StageResult.Finding.builder()
                    .title("代码质量：使用标准输出")
                    .severity("LOW")
                    .description("应使用日志框架代替 System.out/err.println")
                    .build());
        }

        return findings;
    }

    /**
     * 使用 LLM 对修复后的代码进行深度审查
     *
     * @param request   代码修复请求
     * @param fixedCode 修复后的代码
     * @return LLM 审查评分结果，失败时返回 null
     */
    private ReviewScore reviewWithLlm(CodeFixRequest request, String fixedCode) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("请审查以下代码修复，从代码质量、安全风险、性能和可维护性维度评估。\n\n");
            prompt.append("## 原始问题\n");
            prompt.append(request.getDescription()).append("\n\n");
            prompt.append("## 修复代码\n");
            prompt.append(fixedCode).append("\n\n");
            prompt.append("请以 JSON 格式返回：\n");
            prompt.append("- score: 0-100 评分\n");
            prompt.append("- issues: 问题数组，每个包含 title, severity, description\n");

            String result = llmService.generateOrDefault(
                    "你是一个资深代码审查专家。", prompt.toString(), "{}");

            List<StageResult.Finding> findings = new ArrayList<>();
            if (result != null && !result.isBlank() && result.length() > 10) {
                findings.add(StageResult.Finding.builder()
                        .title("LLM 审查建议")
                        .severity("LOW")
                        .description(truncate(result, 500))
                        .build());
            }

            return new ReviewScore(findings);

        } catch (Exception e) {
            log.warn("[CODE_REVIEW] LLM 审查失败，跳过: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 根据发现的问题计算审查评分，初始100分，按问题严重程度扣分
     *
     * @param findings 发现的问题列表
     * @return 审查评分（0-100）
     */
    private double calculateReviewScore(List<StageResult.Finding> findings) {
        double score = 100.0;
        for (StageResult.Finding finding : findings) {
            switch (finding.getSeverity()) {
                case "HIGH" -> score -= 20;
                case "MEDIUM" -> score -= 10;
                case "LOW" -> score -= 5;
            }
        }
        return Math.max(0, score);
    }

    /**
     * 截断文本到指定最大长度
     *
     * @param text   原始文本
     * @param maxLen 最大长度
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLen) {
        if (text == null)
            return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /** 审查评分结果，包含 LLM 审查发现的问题列表 */
    private record ReviewScore(List<StageResult.Finding> findings) {
    }
}
