package io.yunxi.platform.framework.workspace;

import io.yunxi.platform.framework.agent.AgentWorkspaceInitializer;
import io.yunxi.platform.framework.workspace.model.SceneDetectionRule;
import io.yunxi.platform.framework.workspace.model.WorkspaceConfig;
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
 * 工作区自动发现引擎
 *
 * <p>在应用启动时自动扫描所有已注册 Agent 的工作区，完成以下发现：</p>
 * <ul>
 *   <li>解析 AGENTS.md → 场景检测规则（替代 {@code SceneContributor} SPI）</li>
 *   <li>扫描 knowledge/ → 知识库文件列表</li>
 *   <li>扫描 skills/ → 技能注册</li>
 *   <li>扫描 subagents/ → 子智能体发现</li>
 *   <li>验证工作区完整性 → 启动时报告缺失</li>
 * </ul>
 *
 * <p>与 {@link AgentWorkspaceInitializer} 的区别：</p>
 * <ul>
 *   <li>Initializer：启动时创建目录结构和默认文件（写操作）</li>
 *   <li>DiscoveryEngine：启动时扫描读取现有工作区内容（读操作），来配置框架行为</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 */
@Component
public class WorkspaceAutoDiscoveryEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAutoDiscoveryEngine.class);

    /** AGENTS.md 解析器 */
    private final SceneDetectionParser sceneDetectionParser;

    /** 所有已发现的工作区配置（agentName → WorkspaceConfig） */
    private final Map<String, WorkspaceConfig> discoveredWorkspaces = new LinkedHashMap<>();

    /** 所有已发现的场景检测规则（sceneName → SceneDetectionRule） */
    private final Map<String, SceneDetectionRule> discoveredSceneRules = new LinkedHashMap<>();

    /** 默认工作区基础路径 */
    private final String defaultWorkspaceBase;

    public WorkspaceAutoDiscoveryEngine() {
        this.sceneDetectionParser = new SceneDetectionParser();
        this.defaultWorkspaceBase = "./.agentscope/workspace";
    }

    /**
     * 应用启动完成后执行工作区自动发现
     */
    @EventListener(ApplicationReadyEvent.class)
    public void discoverAll() {
        log.info("WorkspaceAutoDiscoveryEngine: 开始扫描工作区...");

        // 扫描默认工作区目录下的所有 Agent 工作区
        Path workspaceRoot = Path.of(defaultWorkspaceBase);
        if (!Files.exists(workspaceRoot)) {
            log.info("工作区根目录不存在: {}，跳过自动发现", workspaceRoot);
            return;
        }

        try (Stream<Path> dirs = Files.list(workspaceRoot)) {
            dirs.filter(Files::isDirectory)
                    .forEach(this::discoverSingleWorkspace);
        } catch (IOException e) {
            log.warn("扫描工作区根目录失败: {}", workspaceRoot, e);
        }

        log.info("WorkspaceAutoDiscoveryEngine: 扫描完成，发现 {} 个工作区, {} 个场景规则",
                discoveredWorkspaces.size(), discoveredSceneRules.size());
    }

    /**
     * 发现单个 Agent 工作区
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
                    log.info("发现知识文件: agent={}, files={}", agentName, knowledgeFiles);
                }
            } catch (IOException e) {
                log.warn("扫描 knowledge/ 失败: {}", knowledgeDir, e);
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
                log.warn("扫描 skills/ 失败: {}", skillsDir, e);
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
                log.warn("扫描 subagents/ 失败: {}", subAgentsDir, e);
            }
        }

        // 5. 验证完整性
        validateWorkspace(config);

        discoveredWorkspaces.put(agentName, config);
    }

    /**
     * 验证工作区完整性
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
            config.setValidationMessage("工作区完整");
        } else {
            config.setValid(false);
            config.setValidationMessage(String.join("; ", issues));
            log.warn("工作区验证失败: agent={}, issues={}", config.getAgentName(), issues);
        }
    }

    // ===== 查询方法 =====

    /**
     * 获取指定 Agent 的工作区配置
     */
    public Optional<WorkspaceConfig> getWorkspaceConfig(String agentName) {
        return Optional.ofNullable(discoveredWorkspaces.get(agentName));
    }

    /**
     * 获取所有场景检测规则
     */
    public Map<String, SceneDetectionRule> getDiscoveredSceneRules() {
        return Collections.unmodifiableMap(discoveredSceneRules);
    }

    /**
     * 按场景名称获取场景检测规则
     */
    public Optional<SceneDetectionRule> getSceneRule(String sceneName) {
        return Optional.ofNullable(discoveredSceneRules.get(sceneName));
    }

    /**
     * 获取所有已发现的工作区配置
     */
    public Map<String, WorkspaceConfig> getDiscoveredWorkspaces() {
        return Collections.unmodifiableMap(discoveredWorkspaces);
    }
}