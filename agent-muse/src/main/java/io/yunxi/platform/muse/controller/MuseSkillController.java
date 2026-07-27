package io.yunxi.platform.muse.controller;

import io.yunxi.platform.muse.bootstrap.BuiltinSkillLoader;
import io.yunxi.platform.muse.config.EvolutionConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/**
 * MUSE 技能管理 — 运行时触发的内建技能重新落地。
 *
 * <p>内建技能打包在 JAR classpath 中，正常只在启动时由
 * {@link BuiltinSkillLoader#run} 复制一次。通过此端点可在运行时按需重新落地
 * （例如清除了磁盘技能后恢复、或排查内建技能版本问题）。</p>
 *
 * <p>注意：落地后的技能由 AgentScope {@code DynamicSkillMiddleware} 在下次对话时
 * 自动重新扫描，无需额外操作。</p>
 *
 * <p>仅在 yunxi.muse.enabled=true 时生效。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/muse/skills")
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MuseSkillController {

    private final BuiltinSkillLoader builtinSkillLoader;
    private final EvolutionConfig config;

    /**
     * 触发内建技能从 classpath 重新落地到磁盘（覆盖已有 SKILL.md）。
     *
     * @return 落地结果（技能数、目标目录）
     */
    @PostMapping("/reload-from-classpath")
    public Map<String, Object> reloadBuiltinSkills() {
        log.info("[muse] 收到运行时重新落地内建技能请求");
        BuiltinSkillLoader.BuiltinCopyResult result = builtinSkillLoader.copyBuiltinSkills();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", result.count());
        response.put("targetDir", result.targetDir());
        if (result.error() != null) {
            response.put("success", false);
            response.put("error", result.error());
        } else {
            response.put("success", true);
            response.put("note", "技能已落地到 projectGlobalSkillsDir，DynamicSkillMiddleware 在下次对话时自动重新扫描");
        }
        return response;
    }

    /**
     * 查看 MUSE 配置信息（内建技能导出目录、复制策略）。
     */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", config.isEnabled());
        result.put("builtinExportDir", config.getBuiltinExportDir());
        result.put("builtinCopyOnStart", config.isBuiltinCopyOnStart());
        return result;
    }
}
