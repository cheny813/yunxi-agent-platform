package io.yunxi.platform.ingress.controller.muse;

import io.yunxi.platform.spi.muse.MuseSkillAdminService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MUSE 技能管理 REST 端点（统一接入网关收编入口）。
 *
 * <p>原位于 agent-muse 的 io.yunxi.platform.muse.controller.MuseSkillController，违反「外部 HTTP 入口
 * 必须集中到统一接入网关」的架构不变量（GatewayArchitectureTest 注释将其列为遗留运维端点）。收编后本控制器
 * 仅委托 {@link MuseSkillAdminService}（定义在 agent-spi 的平台契约），不依赖 muse 内部类型。仅当
 * yunxi.muse.enabled=true 时注册，与 muse 侧实现的 {@code @ConditionalOnProperty} 同源。</p>
 */
@RestController
@RequestMapping("/api/muse/skills")
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
public class MuseSkillController {

    private final MuseSkillAdminService adminService;

    public MuseSkillController(MuseSkillAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/reload-from-classpath")
    public Map<String, Object> reloadBuiltinSkills() {
        MuseSkillAdminService.MuseSkillCopyResult result = adminService.reloadBuiltinSkills();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", result.count());
        response.put("targetDir", result.targetDir());
        response.put("error", result.error());
        if (result.error() == null || result.error().isBlank()) {
            response.put("success", true);
            response.put("note", "技能已落地到 projectGlobalSkillsDir，DynamicSkillMiddleware 在下次对话时自动重新扫描");
        } else {
            response.put("success", false);
        }
        return response;
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        MuseSkillAdminService.MuseSkillConfigView config = adminService.getConfig();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", config.enabled());
        response.put("builtinExportDir", config.builtinExportDir());
        response.put("builtinCopyOnStart", config.builtinCopyOnStart());
        return response;
    }
}
