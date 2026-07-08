package io.yunxi.platform.agent.workspace;

import io.yunxi.platform.agent.workspace.model.SceneDetectionRule;
import io.yunxi.platform.agent.workspace.model.WorkspaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 工作空间自动发现引擎
 * <p>
 * 在应用启动时检测所有注册 Agent 的工作空间，包括：
 * </p>
 * <ul>
 * <li>解析 AGENTS.md → 场景匹配规则（替代 SceneContributor SPI）</li>
 * <li>扫描 knowledge/ → 知识文档列表</li>
 * <li>扫描 skills/ → 技能列表</li>
 * <li>扫描 subagents/ → 子智能体发现</li>
 * <li>验证工作空间合法性 → 启动时校验</li>
 * </ul>
 *
 * <p>
 * 与 {@link AgentWorkspaceInitializer} 的分工：
 * </p>
 * <ul>
 * <li>Initializer：启动时创建基础目录结构和默认文件（写操作）</li>
 * <li>DiscoveryEngine：启动时扫描获取现有工作空间内容（读操作），用于配置框架行为</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 */
@Component
public class WorkspaceAutoDiscoveryEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAutoDiscoveryEngine.class);

    /** AGENTS.md 解析器 */
    private final SceneDetectionParser sceneDetectionParser;

    /** 所有发现的工作空间配置（agentName → WorkspaceConfig） */
    private final Map<String, WorkspaceConfig> discoveredWorkspaces = new LinkedHashMap<>();

    /** 所有发现的场景匹配规则（sceneName → SceneDetectionRule） */
    private final Map<String, SceneDetectionRule> discoveredSceneRules = new LinkedHashMap<>();

    /** 默认工作空间基础路径 */
    private final String defaultWorkspaceBase;

    public WorkspaceAutoDiscoveryEngine() {
        this.sceneDetectionParser = new SceneDetectionParser();
        this.defaultWorkspaceBase = "./.agentscope/workspace";
    }

    /**
     * 应用启动后执行工作空间自动发现
     */
    @EventListener(ApplicationReadyEvent.class)
    public void discoverAll() {
        log.info("WorkspaceAutoDiscoveryEngine: 开始扫描工作空间...");

        // 扫描默认工作空间目录下的所有Agent 工作空间
        Path workspaceRoot = Path.of(defaultWorkspaceBase);
        if (!Files.exists(workspaceRoot)) {
            log.info("工作空间根目录不存在 {}，跳过扫描", workspaceRoot);
            return;
        }

        try (Stream<Path> dirs = Files.list(workspaceRoot)) {
            dirs.filter(Files::isDirectory)
                    .forEach(this::discoverOrRecurse);
        } catch (IOException e) {
            log.warn("扫描工作空间根目录异常: {}", workspaceRoot, e);
        }

        log.info("WorkspaceAutoDiscoveryEngine: 扫描完成，发现{}个工作空间，{}个场景规则",
                discoveredWorkspaces.size(), discoveredSceneRules.size());
    }

    /**
     * 扫描工作空间根目录，跳过非 Agent 目录（如 skills/）。
     * users/ 按框架设计嵌套在 agent 目录下（{agentName}/users/{userId}/），
     * 由 WorkspaceManager 运行时管理，不在根级别处理。
     */
    private void discoverOrRecurse(Path dir) {
        String name = dir.getFileName().toString();
        if ("skills".equals(name)) {
            return;
        }
        discoverSingleWorkspace(dir);
    }

    /**
     * 发现单个 Agent 工作空间
     */
    private void discoverSingleWorkspace(Path workspaceDir) {
        String agentName = workspaceDir.getFileName().toString();
        WorkspaceConfig config = new WorkspaceConfig(workspaceDir.toString(), agentName);

        // 1. 解析 AGENTS.md
        Path agentsMd = workspaceDir.resolve("AGENTS.md");
        if (Files.exists(agentsMd)) {
            SceneDetectionRule rule = sceneDetectionParser.parse(agentsMd);
            config.setSceneRule(rule);
            if (rule != null) {
                discoveredSceneRules.put(rule.getSceneName(), rule);
                log.info("发现场景规则: agent={}, scene={}, keywords={}",
                        agentName, rule.getSceneName(), rule.getKeywords());
            }
        }

        // 2. 扫描 knowledge/
        Path knowledgeDir = workspaceDir.resolve("knowledge");
        if (Files.exists(knowledgeDir) && Files.isDirectory(knowledgeDir)) {
            try (Stream<Path> files = Files.list(knowledgeDir)) {
                List<String> knowledgeFiles = files
                        .filter(f -> !Files.isDirectory(f))
                        .map(f -> f.getFileName().toString())
                        .collect(Collectors.toList());
                config.setKnowledgeFiles(knowledgeFiles);
                if (!knowledgeFiles.isEmpty()) {
                    log.info("发现知识文档: agent={}, files={}", agentName, knowledgeFiles);
                }
            } catch (IOException e) {
                log.warn("扫描 knowledge/ 异常: {}", knowledgeDir, e);
            }
        }

        // 3. 扫描 skills/
        Path skillsDir = workspaceDir.resolve("skills");
        if (Files.exists(skillsDir) && Files.isDirectory(skillsDir)) {
            try (Stream<Path> dirs = Files.list(skillsDir)) {
                List<String> skillNames = dirs
                        .filter(Files::isDirectory)
                        .map(d -> d.getFileName().toString())
                        .collect(Collectors.toList());
                config.setSkillNames(skillNames);
                if (!skillNames.isEmpty()) {
                    log.info("发现技能: agent={}, skills={}", agentName, skillNames);
                }
            } catch (IOException e) {
                log.warn("扫描 skills/ 异常: {}", skillsDir, e);
            }
        }

        // 4. 扫描 subagents/
        Path subAgentsDir = workspaceDir.resolve("subagents");
        if (Files.exists(subAgentsDir) && Files.isDirectory(subAgentsDir)) {
            try (Stream<Path> files = Files.list(subAgentsDir)) {
                List<String> subAgentIds = files
                        .filter(f -> f.toString().endsWith(".md"))
                        .map(f -> {
                            String name = f.getFileName().toString();
                            return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
                        })
                        .collect(Collectors.toList());
                config.setSubAgentIds(subAgentIds);
                if (!subAgentIds.isEmpty()) {
                    log.info("发现子智能体: agent={}, subagents={}", agentName, subAgentIds);
                }
            } catch (IOException e) {
                log.warn("扫描 subagents/ 异常: {}", subAgentsDir, e);
            }
        }

        // 5. 验证完整性
        validateWorkspace(config);

        discoveredWorkspaces.put(agentName, config);
    }

    /**
     * 验证工作空间完整性
     */
    public void validateWorkspace(WorkspaceConfig config) {
        List<String> issues = new ArrayList<>();
        Path root = Path.of(config.getWorkspacePath());

        // AGENTS.md 必须存在
        if (!Files.exists(root.resolve("AGENTS.md"))) {
            issues.add("缺少 AGENTS.md（Agent 身份描述）");
        }

        // knowledge/ 必须存在（可为空）
        if (!Files.exists(root.resolve("knowledge"))) {
            issues.add("缺少 knowledge/ 目录");
        }

        if (issues.isEmpty()) {
            config.setValid(true);
            config.setValidationMessage("工作空间有效");
        } else {
            config.setValid(false);
            config.setValidationMessage(String.join("; ", issues));
            log.warn("工作空间验证失败: agent={}, issues={}", config.getAgentName(), issues);
        }
    }

    // ===== 查询方法 =====

    /**
     * 获取指定 Agent 的工作空间配置
     */
    public Optional<WorkspaceConfig> getWorkspaceConfig(String agentName) {
        return Optional.ofNullable(discoveredWorkspaces.get(agentName));
    }

    /**
     * 获取所有场景匹配规则
     */
    public Map<String, SceneDetectionRule> getDiscoveredSceneRules() {
        return Collections.unmodifiableMap(discoveredSceneRules);
    }

    /**
     * 按场景名称获取场景匹配规则
     */
    public Optional<SceneDetectionRule> getSceneRule(String sceneName) {
        return Optional.ofNullable(discoveredSceneRules.get(sceneName));
    }

    /**
     * 获取所有发现的工作空间配置
     */
    public Map<String, WorkspaceConfig> getDiscoveredWorkspaces() {
        return Collections.unmodifiableMap(discoveredWorkspaces);
    }
}