package io.yunxi.platform.framework.profile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职业阶段注册表（唯一数据源）
 *
 * <p>所有职业阶段（内置 + 自定义）统一通过此注册表管理。
 * 内置阶段通过 application.yml 的 {@code career-stage.builtins} 配置注册。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class CareerStageRegistry {

    /** 职业阶段数据映射 */
    private final Map<String, StageEntry> stages = new LinkedHashMap<>();

    /**
     * 构造函数
     *
     * @param builtins 内置阶段配置字符串
     */
    public CareerStageRegistry(@Value("${career-stage.builtins:}") String builtins) {
        loadBuiltins(builtins);
    }

    /**
     * 从配置加载内置阶段
     * 格式: "TRAINEE:实习生:0:1;JUNIOR:初级:1:3;MIDDLE:中级:4:8;SENIOR:高级:9:15;EXPERT:专家:16:999"
     */
    private void loadBuiltins(String builtins) {
        if (builtins == null || builtins.isBlank()) {
            // 默认内置阶段
            stages.put(CareerStage.TRAINEE, new StageEntry(CareerStage.TRAINEE, "实习生", 0, 1, true));
            stages.put(CareerStage.JUNIOR, new StageEntry(CareerStage.JUNIOR, "初级", 1, 3, true));
            stages.put(CareerStage.MIDDLE, new StageEntry(CareerStage.MIDDLE, "中级", 4, 8, true));
            stages.put(CareerStage.SENIOR, new StageEntry(CareerStage.SENIOR, "高级", 9, 15, true));
            stages.put(CareerStage.EXPERT, new StageEntry(CareerStage.EXPERT, "专家", 16, 999, true));
            log.info("使用默认内置职业阶段: {} 个", stages.size());
            return;
        }

        for (String entry : builtins.split(";")) {
            String[] parts = entry.trim().split(":", 4);
            if (parts.length >= 2) {
                String name = parts[0].trim();
                String displayName = parts[1].trim();
                int minYears = parts.length >= 3 ? parseInt(parts[2].trim(), 0) : 0;
                int maxYears = parts.length >= 4 ? parseInt(parts[3].trim(), 999) : 999;
                stages.put(name, new StageEntry(name, displayName, minYears, maxYears, true));
                log.debug("加载内置职业阶段: {} ({})", displayName, name);
            }
        }
        log.info("内置职业阶段加载完成: {} 个", stages.size());
    }

    private int parseInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 根据从业年限确定职业阶段
     *
     * @return 阶段标识字符串
     */
    public String fromExperienceYears(int years) {
        for (StageEntry se : stages.values()) {
            if (years >= se.minYears && years <= se.maxYears) {
                return se.name;
            }
        }
        return CareerStage.EXPERT;
    }

    /**
     * 获取阶段显示名称
     */
    public String getDisplayName(String name) {
        StageEntry se = stages.get(name);
        return se != null ? se.displayName : name;
    }

    /**
     * 获取阶段最小年限
     */
    public int getMinYears(String name) {
        StageEntry se = stages.get(name);
        return se != null ? se.minYears : 0;
    }

    /**
     * 获取阶段最大年限
     */
    public int getMaxYears(String name) {
        StageEntry se = stages.get(name);
        return se != null ? se.maxYears : 999;
    }

    /**
     * 获取所有已注册阶段
     */
    public Map<String, StageEntry> getStages() {
        return stages;
    }

    /**
     * 阶段数据
     */
    public record StageEntry(String name, String displayName, int minYears, int maxYears, boolean builtin) {}
}
