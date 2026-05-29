package io.yunxi.platform.framework.workspace;

import io.yunxi.platform.framework.workspace.model.SceneDetectionRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AGENTS.md 解析器
 *
 * <p>从工作区的 AGENTS.md 文件中提取场景检测规则和行为约定，替代 {@code SceneContributor} SPI。
 * 解析规则基于 Markdown 标题段落：</p>
 *
 * <pre>
 * # 场景检测
 * - 场景: order_query
 * - 触发关键词: 订单, 物流, 快递, 发货
 * - 场景上下文: 你是一个电商订单查询助手，精通订单查询和物流跟踪。
 * - 优先级: 10
 *
 * # 行为约定
 * - 优先调用 order-mcp 的 queryOrder 工具
 * - 输出格式: Markdown 表格
 * </pre>
 *
 * @author yunxi-agent-platform
 */
public class SceneDetectionParser {

    private static final Logger log = LoggerFactory.getLogger(SceneDetectionParser.class);

    // 场景段落的 Markdown 标题
    private static final String SCENE_SECTION_HEADER = "# 场景检测";

    // 关键词行格式: "- 触发关键词: xxx, yyy, zzz"
    private static final Pattern KEYWORDS_PATTERN = Pattern.compile(
            "-\\s*触发关键词\\s*[:：]\\s*(.+)",
            Pattern.CASE_INSENSITIVE);

    // 场景名称行格式: "- 场景: xxx"
    private static final Pattern SCENE_NAME_PATTERN = Pattern.compile(
            "-\\s*场景\\s*[:：]\\s*(.+)",
            Pattern.CASE_INSENSITIVE);

    // 场景上下文行格式: "- 场景上下文: xxx"
    private static final Pattern CONTEXT_PATTERN = Pattern.compile(
            "-\\s*场景上下文\\s*[:：]\\s*(.+)",
            Pattern.CASE_INSENSITIVE);

    // 优先级行格式: "- 优先级: 10"
    private static final Pattern PRIORITY_PATTERN = Pattern.compile(
            "-\\s*优先级\\s*[:：]\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 从 AGENTS.md 文件解析场景检测规则
     *
     * @param agentsMdPath AGENTS.md 文件路径
     * @return 解析出的场景检测规则，如果文件不存在或未配置场景则返回 null
     */
    public SceneDetectionRule parse(Path agentsMdPath) {
        if (agentsMdPath == null || !Files.exists(agentsMdPath)) {
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(agentsMdPath);
            return parseLines(lines);
        } catch (IOException e) {
            log.warn("读取 AGENTS.md 失败: {}", agentsMdPath, e);
            return null;
        }
    }

    /**
     * 从 AGENTS.md 文件路径字符串解析场景检测规则
     */
    public SceneDetectionRule parse(String agentsMdPath) {
        if (agentsMdPath == null) {
            return null;
        }
        return parse(Path.of(agentsMdPath));
    }

    /**
     * 按行解析场景检测规则
     */
    SceneDetectionRule parseLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }

        // 找到 # 场景检测 段落的起始和结束位置
        int sectionStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(SCENE_SECTION_HEADER)) {
                sectionStart = i;
                break;
            }
        }

        if (sectionStart < 0) {
            return null;
        }

        // 提取段落内容（直到下一个顶级标题或文件末尾）
        List<String> sectionLines = new ArrayList<>();
        for (int i = sectionStart + 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("# ") && !line.startsWith("# " + SCENE_SECTION_HEADER.substring(2))) {
                break; // 遇到下一个顶级标题结束
            }
            sectionLines.add(line);
        }

        // 解析各字段
        String sceneName = extractField(sectionLines, SCENE_NAME_PATTERN);
        if (sceneName == null) {
            log.debug("AGENTS.md 中未配置场景名称，跳过场景检测解析");
            return null;
        }

        String keywordsStr = extractField(sectionLines, KEYWORDS_PATTERN);
        List<String> keywords = keywordsStr != null
                ? List.of(keywordsStr.split("\\s*[,，]\\s*"))
                : Collections.emptyList();

        String context = extractField(sectionLines, CONTEXT_PATTERN);

        int priority = 100;
        String priorityStr = extractField(sectionLines, PRIORITY_PATTERN);
        if (priorityStr != null) {
            try {
                priority = Integer.parseInt(priorityStr);
            } catch (NumberFormatException e) {
                log.warn("AGENTS.md 中优先级格式无效: {}", priorityStr);
            }
        }

        log.info("从 AGENTS.md 解析到场景: name={}, keywords={}, priority={}", sceneName, keywords, priority);
        return new SceneDetectionRule(sceneName, keywords, context, priority);
    }

    /**
     * 从段落行中提取匹配正则表达式的第一个字段值
     */
    private String extractField(List<String> lines, Pattern pattern) {
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }
}