package io.yunxi.platform.agent.profile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职业阶段注册表（唯一数据源）
 * <p>所有职业阶段（内置 + 自定义）统一通过此注册表管理。
 * 内置阶段通过 application.yml 的 {@code career-stage.builtins} 配置注册。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Component
public class CareerStageRegistry {

    private final Map<String, StageEntry> stages = new LinkedHashMap<>();

    /**
     * 构造职业阶段注册表。
     *
     * <p>从配置属性 {@code career-stage.builtins} 解析内置阶段定义并加载；配置为空时使用默认 5 个阶段。</p>
     *
     * @param builtins 内置阶段定义配置字符串（{@code name:displayName:minYears:maxYears}，多条以 {@code ;} 分隔）
     */
    public CareerStageRegistry(@Value("${career-stage.builtins:}") String builtins) {
        loadBuiltins(builtins);
    }

    /**
     * 加载内置职业阶段。
     *
     * <p>当配置为空时，使用平台内置的 5 个默认阶段（实习生/初级/中级/高级/专家）；
     * 否则按 {@code name:displayName:minYears:maxYears} 格式逐条解析并注册。</p>
     *
     * @param builtins 配置文件注入的内置阶段字符串，多个以 {@code ;} 分隔
     */
    private void loadBuiltins(String builtins) {
        if (builtins == null || builtins.isBlank()) {
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
            }
        }
        log.info("内置职业阶段加载完成: {} 个", stages.size());
    }

    /**
     * 将字符串安全解析为 int，解析失败时返回默认值。
     *
     * @param s 待解析字符串
     * @param defaultValue 解析失败时的回退值
     * @return 解析得到的整数或默认值
     */
    private int parseInt(String s, int defaultValue) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * 根据工作年限推断对应的职业阶段。
     *
     * @param years 工作年限
     * @return 匹配的阶段标识；无匹配时回退到 {@link CareerStage#EXPERT}
     */
    public String fromExperienceYears(int years) {
        for (StageEntry se : stages.values()) {
            if (years >= se.minYears && years <= se.maxYears) return se.name;
        }
        return CareerStage.EXPERT;
    }

    /**
     * 获取阶段的展示名称。
     *
     * @param name 阶段标识
     * @return 展示名称；阶段不存在时原样返回标识本身
     */
    public String getDisplayName(String name) {
        StageEntry se = stages.get(name);
        return se != null ? se.displayName : name;
    }

    /**
     * 获取阶段的最小年限。
     *
     * @param name 阶段标识
     * @return 最小年限；阶段不存在时返回 0
     */
    public int getMinYears(String name) {
        StageEntry se = stages.get(name);
        return se != null ? se.minYears : 0;
    }

    /**
     * 获取阶段的最大年限。
     *
     * @param name 阶段标识
     * @return 最大年限；阶段不存在时返回 999
     */
    public int getMaxYears(String name) {
        StageEntry se = stages.get(name);
        return se != null ? se.maxYears : 999;
    }

    /**
     * 返回所有已注册阶段的只读视图。
     *
     * @return 阶段标识到阶段条目的映射
     */
    public Map<String, StageEntry> getStages() { return stages; }

    /** 职业阶段条目，记录单个阶段的标识、展示名与年限区间。
     * @param name        阶段标识（如 TRAINEE）
     * @param displayName 展示名称（如"实习生"）
     * @param minYears    最小工作年限（含）
     * @param maxYears    最大工作年限（含）
     * @param builtin     是否为内置阶段
     */
    public record StageEntry(String name, String displayName, int minYears, int maxYears, boolean builtin) {}
}
