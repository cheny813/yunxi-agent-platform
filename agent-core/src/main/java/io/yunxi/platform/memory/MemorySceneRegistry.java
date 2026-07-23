package io.yunxi.platform.memory;

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
 * 所有场景（内置 + 自定义）统一通过注册表管理。
 * 内置场景通过 application.yml 的 {@code memory.scene.builtins} 配置注册，
 * 业务层通过 {@link #register(String, String, String, int, List)} 动态注册自定义场景。
 * </p>
 *
 * @author yunxi-platform
 */
@Slf4j
@Component
public class MemorySceneRegistry {

    /** 场景数据映射（name → SceneEntry），有序保证优先级 */
    private final Map<String, SceneEntry> scenes = new LinkedHashMap<>();

    /**
     * 构造函数
     *
     * <p>
     * 从 application.yml 的 {@code memory.scene.builtins} 配置加载内置场景。
     * 格式示例：{@code PERSONAL_ASSISTANT:个人助手:生活、工作、家庭、情感:-1;GENERAL:通用:通用对话:7}
     * 未配置时使用默认内置场景（PERSONAL_ASSISTANT、GENERAL）。
     * </p>
     *
     * @param builtins 内置场景配置字符串（分号分隔，每段冒号分隔各字段）
     */
    public MemorySceneRegistry(@Value("${memory.scene.builtins:}") String builtins) {
        loadBuiltins(builtins);
    }

    /**
     * 从配置字符串加载内置场景
     * <p>
     * 每条场景格式：name:displayName:description:retentionDays:keywords
     * retentionDays=-1 表示永久保留（如个人助手场景）。
     * keywords 用中文顿号或逗号分隔。
     * </p>
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
                        ? List.of(parts[4].trim().split("[、，]"))
                        : null;
                scenes.put(name, new SceneEntry(name, displayName, description, retentionDays, keywords, true));
                log.debug("加载内置场景: {} ({})", displayName, name);
            }
        }
        log.info("内置场景已加载: {} 个", scenes.size());
    }

    /**
     * 安全解析整数，解析失败返回默认值
     */
    private int parseInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 注册自定义场景
     *
     * <p>
     * 业务层在初始化时调用此方法动态注册场景（如租户业务场景 TENANT_BUSINESS）。
     * 自定义场景可以通过关键词匹配触发，与内置场景共享优先级顺序。
     * </p>
     *
     * @param name          场景标识（如 TENANT_BUSINESS）
     * @param displayName   展示名称（如"租户业务"）
     * @param description   场景描述
     * @param retentionDays 记忆保留天数，-1 永久
     * @param keywords      触发关键词列表（大小写不敏感）
     */
    public void register(String name, String displayName, String description,
            int retentionDays, List<String> keywords) {
        scenes.put(name, new SceneEntry(name, displayName, description, retentionDays, keywords, false));
        log.info("注册自定义记忆场景: {} ({})", displayName, name);
    }

    /**
     * 通过关键词检测当前对话属于哪个场景
     *
     * <p>
     * 遍历所有注册场景（按注册顺序），匹配关键词。
     * 返回第一个匹配的场景名称，未匹配时返回 {@link MemoryScene#GENERAL}。
     * 关键词匹配大小写不敏感。
     * </p>
     *
     * @param text 用户输入文本
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
     * <p>
     * 验证给定名称是否为已注册场景，否则返回 GENERAL。
     * 用于 SDK 的 structured output 等需要枚举值时的兜底逻辑。
     * </p>
     *
     * @param name 场景名称
     * @return 场景标识字符串，未知场景返回 GENERAL
     */
    public String fromName(String name) {
        if (name == null || name.isEmpty()) {
            return MemoryScene.GENERAL;
        }
        return scenes.containsKey(name) ? name : MemoryScene.GENERAL;
    }

    /**
     * 获取场景的展示名称
     *
     * @param name 场景标识
     * @return 展示名称，未知时返回 name 本身
     */
    public String getDisplayName(String name) {
        SceneEntry se = scenes.get(name);
        return se != null ? se.displayName : name;
    }

    /**
     * 获取场景的保留天数
     *
     * @param name 场景标识
     * @return 保留天数，未知时返回默认值 7
     */
    public int getRetentionDays(String name) {
        SceneEntry se = scenes.get(name);
        return se != null ? se.retentionDays : 7;
    }

    /**
     * 判断场景是否为长期保留（永久或超过 30 天）
     *
     * @param name 场景标识
     * @return true 如果场景长期保留
     */
    public boolean isLongTerm(String name) {
        SceneEntry se = scenes.get(name);
        return se != null && (se.retentionDays == -1 || se.retentionDays > 30);
    }

    /**
     * 获取所有已注册场景（含内置和自定义）
     *
     * @return 只读的场景映射
     */
    public Map<String, SceneEntry> getScenes() {
        return scenes;
    }

    /**
     * 仅获取自定义场景（排除内置场景）
     *
     * @return 自定义场景映射
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
     * 场景数据记录
     *
     * @param name          场景标识
     * @param displayName   展示名称
     * @param description   场景描述
     * @param retentionDays 记忆保留天数，-1 永久
     * @param keywords      触发关键词列表
     * @param builtin       是否为内置场景
     */
    public record SceneEntry(String name, String displayName, String description,
            int retentionDays, List<String> keywords, boolean builtin) {
    }
}
