package io.yunxi.platform.framework.profile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职业注册表（唯一数据源）
 *
 * <p>
 * 所有职业统一通过此注册表管理。
 * 内置职业从 {@link ConceptRegistry} 中 category=PROFESSION 的概念加载，
 * 业务层通过 {@link #register(String, String, String)} 动态注册自定义职业。
 * </p>
 *
 * <p>
 * 职业标识统一为 String，不再依赖枚举，可自由扩展。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 3.0.0
 */
@Slf4j
@Component
public class ProfessionRegistry {

    /** 概念注册表 */
    private final ConceptRegistry conceptRegistry;

    /** 职业数据映射 */
    private final Map<String, ProfessionEntry> professions = new LinkedHashMap<>();

    /**
     * 构造函数
     *
     * @param conceptRegistry 概念注册表
     */
    @Autowired
    public ProfessionRegistry(ConceptRegistry conceptRegistry) {
        this.conceptRegistry = conceptRegistry;
    }

    @PostConstruct
    private void init() {
        loadFromConcepts();
    }

    /**
     * 从 ConceptRegistry 加载 category=PROFESSION 的概念
     */
    private void loadFromConcepts() {
        List<ConceptRegistry.ConceptEntry> professionConcepts = conceptRegistry.getByCategory("PROFESSION");
        for (ConceptRegistry.ConceptEntry concept : professionConcepts) {
            professions.put(concept.getName(),
                    new ProfessionEntry(concept.getName(), concept.getDisplayName(), concept.getKeywords(), true));
            log.debug("从 ConceptRegistry 加载职业: {} ({})", concept.getDisplayName(), concept.getName());
        }
        log.info("从 ConceptRegistry 加载内置职业: {} 个", professions.size());
    }

    /**
     * 注册自定义职业
     *
     * @param name        职业标识（如 NUTRITIONIST）
     * @param displayName 显示名称（如 "营养师"）
     * @param keywords    关键词（顿号分隔，如 "营养、食谱、健康、膳食"）
     */
    public void register(String name, String displayName, String keywords) {
        professions.put(name, new ProfessionEntry(name, displayName, keywords, false));
        // 同步注册到 ConceptRegistry
        ConceptRegistry.ConceptEntry entry = new ConceptRegistry.ConceptEntry();
        entry.setName(name);
        entry.setDisplayName(displayName);
        entry.setKeywords(keywords);
        entry.setCategory("PROFESSION");
        conceptRegistry.register(entry);
        log.info("注册自定义职业: {} ({})", displayName, name);
    }

    /**
     * 通过关键词检测职业
     *
     * @return 职业标识字符串，未匹配返回 "OTHER"
     */
    public String detectByKeywords(String text) {
        if (text == null || text.isEmpty()) {
            return Profession.OTHER;
        }

        // 优先使用 ConceptRegistry 的身份识别
        List<String> detected = conceptRegistry.detectIdentitiesByCategory(text, "PROFESSION");
        if (!detected.isEmpty()) {
            return detected.get(0);
        }

        // 回退到本地注册表匹配（自定义职业可能尚未同步）
        String lowerText = text.toLowerCase();
        for (ProfessionEntry pe : professions.values()) {
            if (pe.keywords != null) {
                for (String keyword : pe.keywords.split("、")) {
                    if (lowerText.contains(keyword.toLowerCase())) {
                        return pe.name;
                    }
                }
            }
        }

        return Profession.OTHER;
    }

    /**
     * 获取职业显示名称
     */
    public String getDisplayName(String name) {
        ProfessionEntry pe = professions.get(name);
        return pe != null ? pe.displayName : name;
    }

    /**
     * 获取职业关键词
     */
    public String getKeywords(String name) {
        ProfessionEntry pe = professions.get(name);
        return pe != null ? pe.keywords : null;
    }

    /**
     * 获取所有已注册职业
     */
    public Map<String, ProfessionEntry> getProfessions() {
        return professions;
    }

    /**
     * 职业数据
     */
    public record ProfessionEntry(String name, String displayName, String keywords, boolean builtin) {
    }
}
