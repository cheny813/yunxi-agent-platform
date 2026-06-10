package io.yunxi.platform.agent.workspace;

import io.yunxi.platform.agent.workspace.model.SceneDetectionRule;
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
 * <p>从工作区的 AGENTS.md 文件中提取场景检测规则和行为约定。
 * 解析规则基于 Markdown 标题段落。</p>
 *
 * @author yunxi-agent-platform
 */
public class SceneDetectionParser {

    private static final Logger log = LoggerFactory.getLogger(SceneDetectionParser.class);

    private static final String SCENE_SECTION_HEADER = "# 场景检测";

    private static final Pattern KEYWORDS_PATTERN = Pattern.compile(
            "-\\s*触发关键词\\s*[:：]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCENE_NAME_PATTERN = Pattern.compile(
            "-\\s*场景\\s*[:：]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTEXT_PATTERN = Pattern.compile(
            "-\\s*场景上下文\\s*[:：]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIORITY_PATTERN = Pattern.compile(
            "-\\s*优先级\\s*[:：]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    public SceneDetectionRule parse(Path agentsMdPath) {
        if (agentsMdPath == null || !Files.exists(agentsMdPath)) return null;
        try {
            List<String> lines = Files.readAllLines(agentsMdPath);
            return parseLines(lines);
        } catch (IOException e) {
            log.warn("读取 AGENTS.md 失败: {}", agentsMdPath, e);
            return null;
        }
    }

    public SceneDetectionRule parse(String agentsMdPath) {
        if (agentsMdPath == null) return null;
        return parse(Path.of(agentsMdPath));
    }

    SceneDetectionRule parseLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;

        int sectionStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(SCENE_SECTION_HEADER)) {
                sectionStart = i;
                break;
            }
        }
        if (sectionStart < 0) return null;

        List<String> sectionLines = new ArrayList<>();
        for (int i = sectionStart + 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("# ") && !line.startsWith("# " + SCENE_SECTION_HEADER.substring(2))) break;
            sectionLines.add(line);
        }

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
            try { priority = Integer.parseInt(priorityStr); }
            catch (NumberFormatException e) { log.warn("AGENTS.md 中优先级格式无效: {}", priorityStr); }
        }

        log.info("从 AGENTS.md 解析到场景: name={}, keywords={}, priority={}", sceneName, keywords, priority);
        return new SceneDetectionRule(sceneName, keywords, context, priority);
    }

    private String extractField(List<String> lines, Pattern pattern) {
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) return matcher.group(1).trim();
        }
        return null;
    }
}
