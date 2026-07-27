package io.yunxi.platform.controller;

import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 技能管理控制器 — 运行时查看、刷新技能，无需重启。
 *
 * <h3>动态性说明</h3>
 * <p>AgentScope 的 {@code DynamicSkillMiddleware.reloadSkills()} 在每次
 * {@code agent.call()} 时通过 {@code repo.getAllSkills()} 重新扫描
 * {@code projectGlobalSkillsDir}（Layer 1），新增/修改的技能文件在下一次对话中立即生效。
 * 因此通过管理 API 或手动放置到 skills/ 目录的技能无需重启。</p>
 *
 * <h3>AGENTS.md 与技能的区别</h3>
 * <p>AGENTS.md 位于每个 Agent 的 workspace 根目录
 * （{@code .agentscope/workspace/agents/{name}/AGENTS.md}），定义该 Agent 的 persona 和
 * 本地约定。框架 {@code WorkspaceContextMiddleware} 在每次对话时将其作为
 * {@code <agents_context>} 注入 system prompt。它不是技能目录下的文件，
 * 也不参与技能四层链优先级。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillManagementController {

    private final AgentscopeCoreProperties coreProperties;

    /**
     * 列出当前技能目录下所有已落地技能及其元信息。
     *
     * @return 技能列表（名称、SKILL.md 大小、最后修改时间）
     */
    @GetMapping
    public Map<String, Object> listSkills() {
        Map<String, Object> result = new LinkedHashMap<>();
        AgentscopeCoreProperties.SkillProperties skill = coreProperties.getSkill();
        result.put("enabled", skill != null && skill.isEnabled());

        if (skill == null || skill.getProjectGlobalDir() == null || skill.getProjectGlobalDir().isBlank()) {
            result.put("skillsDir", null);
            result.put("skills", List.of());
            result.put("note", "projectGlobalSkillsDir 未配置");
            return result;
        }

        Path skillsDir = Path.of(skill.getProjectGlobalDir());
        result.put("skillsDir", skillsDir.toAbsolutePath().toString());

        List<Map<String, Object>> skills = new ArrayList<>();
        if (Files.isDirectory(skillsDir)) {
            try (Stream<Path> entries = Files.list(skillsDir)) {
                entries.filter(Files::isDirectory).forEach(dir -> {
                    Path skillMd = dir.resolve("SKILL.md");
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", dir.getFileName().toString());
                    info.put("hasSkillMd", Files.isRegularFile(skillMd));
                    try {
                        info.put("skillMdSize", Files.isRegularFile(skillMd) ? Files.size(skillMd) : 0);
                        info.put("lastModified", Files.getLastModifiedTime(skillMd).toInstant().toString());
                    } catch (IOException ignored) {
                        info.put("skillMdSize", 0);
                        info.put("lastModified", null);
                    }
                    // 列出 tests/ 下文件数
                    Path testsDir = dir.resolve("tests");
                    try {
                        if (Files.isDirectory(testsDir)) {
                            try (Stream<Path> testFiles = Files.list(testsDir)) {
                                info.put("testFiles", testFiles.count());
                            }
                        } else {
                            info.put("testFiles", 0);
                        }
                    } catch (IOException ignored) {
                        info.put("testFiles", 0);
                    }
                    skills.add(info);
                });
            } catch (IOException e) {
                log.warn("扫描技能目录失败: {}", e.getMessage());
            }
        }

        result.put("count", skills.size());
        result.put("skills", skills);
        result.put("note", "DynamicSkillMiddleware 在每次对话时自动重新扫描此目录，新增/修改即时生效");
        return result;
    }

    /**
     * 技能目录健康检查 — 验证目录存在且可读。
     */
    @GetMapping("/health")
    public Map<String, Object> checkHealth() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("timestamp", Instant.now().toString());
        health.put("service", "skill-management");

        AgentscopeCoreProperties.SkillProperties skill = coreProperties.getSkill();
        if (skill == null || !skill.isEnabled()) {
            health.put("status", "DISABLED");
            health.put("note", "技能系统未启用（agentscope.core.skill.enabled=false）");
            return health;
        }

        if (skill.getProjectGlobalDir() == null || skill.getProjectGlobalDir().isBlank()) {
            health.put("status", "UNCONFIGURED");
            health.put("note", "projectGlobalSkillsDir 未配置");
            return health;
        }

        Path dir = Path.of(skill.getProjectGlobalDir());
        if (Files.isDirectory(dir) && Files.isReadable(dir)) {
            health.put("status", "UP");
            health.put("dir", dir.toAbsolutePath().toString());
        } else {
            health.put("status", "DOWN");
            health.put("dir", dir.toAbsolutePath().toString());
            health.put("reason", "目录不存在或不可读");
        }
        return health;
    }
}
