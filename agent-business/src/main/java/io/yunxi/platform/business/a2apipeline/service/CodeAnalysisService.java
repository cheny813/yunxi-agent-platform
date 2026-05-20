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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码分析服务
 * <p>
 * Pipeline Stage 1: 对代码进行静态分析，识别潜在问题。
 * 支持语法错误检测、代码规范检查和问题分类。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class CodeAnalysisService implements PipelineStage {

    /** 智能大模型服务，用于 LLM 驱动的代码分析 */
    @Autowired
    private IntelligentLlmService llmService;

    /**
     * 获取阶段名称
     *
     * @return 阶段名称 "CODE_ANALYSIS"
     */
    @Override
    public String getName() {
        return "CODE_ANALYSIS";
    }

    /**
     * 判断分析阶段是否可执行，作为首个阶段始终可执行
     *
     * @param previousResults 前序阶段的执行结果
     * @return 始终返回 true
     */
    @Override
    public boolean canExecute(Map<String, StageResult> previousResults) {
        return true;
    }

    /**
     * 执行分析阶段（接口方法实现），委托给 analyze 方法
     *
     * @param context         流水线上下文
     * @param previousResults 前序阶段的执行结果
     * @return 阶段执行结果
     */
    @Override
    public StageResult execute(PipelineContext context, Map<String, StageResult> previousResults) {
        return analyze(context);
    }

    /**
     * 验证分析结果是否有效
     *
     * @param result 阶段执行结果
     * @return 结果非空、阶段名称为 CODE_ANALYSIS 且状态不为 null 时返回 true
     */
    @Override
    public boolean validateResult(StageResult result) {
        return result != null
                && "CODE_ANALYSIS".equals(result.getStageName())
                && result.getStatus() != null;
    }

    /**
     * 执行代码分析
     *
     * @param context 流水线上下文
     * @return 阶段结果（包含发现的问题列表）
     */
    public StageResult analyze(PipelineContext context) {
        long startTime = System.currentTimeMillis();
        log.info("[CODE_ANALYSIS] 开始分析: instanceId={}", context.getInstanceId());

        try {
            CodeFixRequest request = context.getRequest();
            List<StageResult.Finding> findings = new ArrayList<>();

            // 1. 从错误日志提取语法错误
            if (request.getContext() != null && request.getContext().getErrorLog() != null) {
                findings.addAll(extractErrorsFromLog(request.getContext().getErrorLog()));
            }

            // 2. 从堆栈跟踪提取错误定位
            if (request.getContext() != null && request.getContext().getStackTrace() != null) {
                findings.addAll(extractErrorsFromStackTrace(request.getContext().getStackTrace()));
            }

            // 3. 使用 LLM 进行深度分析
            if (!findings.isEmpty() || request.getDescription() != null) {
                List<StageResult.Finding> llmFindings = analyzeWithLlm(request);
                findings.addAll(llmFindings);
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("[CODE_ANALYSIS] 分析完成: instanceId={}, findings={}, duration={}ms",
                    context.getInstanceId(), findings.size(), duration);

            return StageResult.builder()
                    .stageName("CODE_ANALYSIS")
                    .status(StageResult.StageStatus.COMPLETED)
                    .startTimeMs(startTime)
                    .endTimeMs(System.currentTimeMillis())
                    .durationMs(duration)
                    .findings(findings)
                    .output(Map.of("findingCount", findings.size()))
                    .build();

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[CODE_ANALYSIS] 分析失败: instanceId={}", context.getInstanceId(), e);

            return StageResult.builder()
                    .stageName("CODE_ANALYSIS")
                    .status(StageResult.StageStatus.FAILED)
                    .startTimeMs(startTime)
                    .endTimeMs(System.currentTimeMillis())
                    .durationMs(duration)
                    .output(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * 从错误日志中提取编译错误和运行时错误
     *
     * @param errorLog 错误日志文本
     * @return 提取到的问题列表
     */
    private List<StageResult.Finding> extractErrorsFromLog(String errorLog) {
        List<StageResult.Finding> findings = new ArrayList<>();

        Pattern compileErrorPattern = Pattern.compile(
                "([^\\s]+\\.java):(\\d+):\\s*(error|warning):\\s*(.+)");
        Matcher matcher = compileErrorPattern.matcher(errorLog);

        while (matcher.find()) {
            findings.add(StageResult.Finding.builder()
                    .title("编译错误")
                    .severity("error".equals(matcher.group(3)) ? "HIGH" : "MEDIUM")
                    .description(matcher.group(4))
                    .filePath(matcher.group(1))
                    .lineNumber(Integer.parseInt(matcher.group(2)))
                    .build());
        }

        Pattern genericErrorPattern = Pattern.compile(
                "ERROR[^\\n]*?at\\s+([^\\s]+)\\(([^:]+):(\\d+)\\)");
        Matcher genericMatcher = genericErrorPattern.matcher(errorLog);

        while (genericMatcher.find()) {
            findings.add(StageResult.Finding.builder()
                    .title("运行时错误")
                    .severity("HIGH")
                    .description("运行时异常: " + genericMatcher.group(1))
                    .filePath(genericMatcher.group(2))
                    .lineNumber(Integer.parseInt(genericMatcher.group(3)))
                    .build());
        }

        return findings;
    }

    /**
     * 从堆栈跟踪中提取异常类型和定位信息
     *
     * @param stackTrace 堆栈跟踪文本
     * @return 提取到的问题列表
     */
    private List<StageResult.Finding> extractErrorsFromStackTrace(String stackTrace) {
        List<StageResult.Finding> findings = new ArrayList<>();

        Pattern exceptionPattern = Pattern.compile("^([\\w.]+Exception|Error):\\s*(.+)",
                Pattern.MULTILINE);
        Matcher exceptionMatcher = exceptionPattern.matcher(stackTrace);

        String exceptionType = null;
        String exceptionMessage = null;
        if (exceptionMatcher.find()) {
            exceptionType = exceptionMatcher.group(1);
            exceptionMessage = exceptionMatcher.group(2);
        }

        Pattern framePattern = Pattern.compile("at\\s+([^\\s]+)\\(([^:]+):(\\d+)\\)");
        Matcher frameMatcher = framePattern.matcher(stackTrace);

        while (frameMatcher.find()) {
            String className = frameMatcher.group(1);
            if (className.startsWith("java.") || className.startsWith("org.springframework.")
                    || className.startsWith("io.agentscope.")) {
                continue;
            }

            findings.add(StageResult.Finding.builder()
                    .title(exceptionType != null ? exceptionType : "堆栈异常")
                    .severity("HIGH")
                    .description(exceptionMessage != null ? exceptionMessage : "堆栈跟踪定位")
                    .filePath(frameMatcher.group(2))
                    .lineNumber(Integer.parseInt(frameMatcher.group(3)))
                    .build());
            break;
        }

        return findings;
    }

    /**
     * 使用 LLM 对代码问题进行深度分析
     *
     * @param request 代码修复请求
     * @return LLM 分析发现的问题列表
     */
    private List<StageResult.Finding> analyzeWithLlm(CodeFixRequest request) {
        List<StageResult.Finding> findings = new ArrayList<>();

        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("请分析以下代码问题描述，识别所有潜在问题并按优先级排序。\n\n");
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
                if (request.getContext().getRelatedFiles() != null
                        && !request.getContext().getRelatedFiles().isEmpty()) {
                    prompt.append("## 相关文件\n");
                    request.getContext().getRelatedFiles().forEach(f -> prompt.append("- ").append(f).append("\n"));
                    prompt.append("\n");
                }
            }

            prompt.append("请以 JSON 数组格式返回分析结果，每个元素包含：\n");
            prompt.append("title, severity(HIGH/MEDIUM/LOW), description, filePath, lineNumber\n");

            String result = llmService.generateOrDefault(
                    "你是一个代码分析专家，负责识别代码中的问题。", prompt.toString(), "[]");

            if (result != null && !result.isBlank()) {
                findings.add(StageResult.Finding.builder()
                        .title("LLM 分析结果")
                        .severity("MEDIUM")
                        .description(truncate(result, 500))
                        .filePath("")
                        .lineNumber(0)
                        .build());
            }

        } catch (Exception e) {
            log.warn("[CODE_ANALYSIS] LLM 分析失败，跳过: {}", e.getMessage());
        }

        return findings;
    }

    /**
     * 截断文本到指定最大长度
     *
     * @param text   原始文本
     * @param maxLen 最大长度
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
