package io.yunxi.platform.framework.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景注册表（唯一数据源）
 *
 * <p>
 * 所有场景（内置 + 自定义）统一通过此注册表管理。
 * 内置场景通过 application.yml 的 {@code memory.scene.builtins} 配置注册，
 * 业务层通过 {@link #register(String, String, String, int, List)} 动态注册自定义场景。
 * </p>
 *
 * <p>
 * 场景标识统一为 String，不再依赖枚举，可自由扩展。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Component
public class MemorySceneRegistry {

    /** 场景数据映射 */
    private final Map<String, SceneEntry> scenes = new LinkedHashMap<>();

    /**
     * 构造函数
     *
     * @param builtins 内置场景配置字符串
     */
    public MemorySceneRegistry(@Value("${memory.scene.builtins:}") String builtins) {
        loadBuiltins(builtins);
    }

    /**
     * 从配置加载内置场景
     * 格式: "PERSONAL_ASSISTANT:个人助手:生活、工作、家庭、情感:-1;GENERAL:通用:通用对话:7"
     */
    private void loadBuiltins(String builtins) {
        if (builtins == null || builtins.isBlank()) {
            // 默认内置场景
            scenes.put(MemoryScene.PERSONAL_ASSISTANT,
                    new SceneEntry(MemoryScene.PERSONAL_ASSISTANT, "个人助手", "生活、工作、家庭、情感", -1, null, true));
            scenes.put(MemoryScene.GENERAL,
                    new SceneEntry(MemoryScene.GENERAL, "通用", "通用对话", 7, null, true));
            log.info("使用默认内置场景: {} 个", scenes.size());
            return;
        }

        for (String entry : builtins.split(";")) {
            String[] parts = entry.trim().split(":", 5);
            if (parts.length >= 2) {
                String name = parts[0].trim();
                String displayName = parts[1].trim();
                String description = parts.length >= 3 ? parts[2].trim() : "";
                int retentionDays = parts.length >= 4 ? parseInt(parts[3].trim(), 7) : 7;
                List<String> keywords = parts.length >= 5 && !parts[4].isBlank()
                        ? List.of(parts[4].trim().split("[、,，]"))
                        : null;
                scenes.put(name, new SceneEntry(name, displayName, description, retentionDays, keywords, true));
                log.debug("加载内置场景: {} ({})", displayName, name);
            }
        }
        log.info("内置场景加载完成: {} 个", scenes.size());
    }

    private int parseInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 注册自定义场景
     */
    public void register(String name, String displayName, String description,
            int retentionDays, List<String> keywords) {
        scenes.put(name, new SceneEntry(name, displayName, description, retentionDays, keywords, false));
        log.info("注册自定义记忆场景: {} ({})", displayName, name);
    }

    /**
     * 通过关键词检测场景
     *
     * @return 场景标识字符串，未匹配返回 GENERAL
     */
    public String detect(String text) {
        if (text == null || text.isEmpty()) {
            return MemoryScene.GENERAL;
        }

        String lowerText = text.toLowerCase();

        for (SceneEntry se : scenes.values()) {
            if (se.keywords != null) {
                for (String keyword : se.keywords) {
                    if (lowerText.contains(keyword.toLowerCase())) {
                        return se.name;
                    }
                }
            }
        }

        return MemoryScene.GENERAL;
    }

    /**
     * 从名称解析场景
     *
     * @return 场景标识字符串，未知场景返回 GENERAL
     */
    public String fromName(String name) {
        if (name == null || name.isEmpty()) {
            return MemoryScene.GENERAL;
        }
        return scenes.containsKey(name) ? name : MemoryScene.GENERAL;
    }

    /**
     * 获取场景显示名称
     */
    public String getDisplayName(String name) {
        SceneEntry se = scenes.get(name);
        return se != null ? se.displayName : name;
    }

    /**
     * 获取场景保留天数
     */
    public int getRetentionDays(String name) {
        SceneEntry se = scenes.get(name);
        return se != null ? se.retentionDays : 7;
    }

    /**
     * 场景是否为长期保留
     */
    public boolean isLongTerm(String name) {
        SceneEntry se = scenes.get(name);
        return se != null && (se.retentionDays == -1 || se.retentionDays > 30);
    }

    /**
     * 获取所有已注册场景
     */
    public Map<String, SceneEntry> getScenes() {
        return scenes;
    }

    /**
     * 获取自定义场景（非内置）
     */
    public Map<String, SceneEntry> getCustomScenes() {
        Map<String, SceneEntry> customs = new LinkedHashMap<>();
        for (Map.Entry<String, SceneEntry> entry : scenes.entrySet()) {
            if (!entry.getValue().builtin) {
                customs.put(entry.getKey(), entry.getValue());
            }
        }
        return customs;
    }

    /**
     * 场景数据
     */
    public record SceneEntry(String name, String displayName, String description,
            int retentionDays, List<String> keywords, boolean builtin) {
    }
}
