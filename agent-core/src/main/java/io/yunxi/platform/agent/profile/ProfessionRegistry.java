package io.yunxi.platform.agent.profile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职业注册表（唯一数据源）
 * <p>所有职业统一通过此注册表管理。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Component
public class ProfessionRegistry {

    private final ConceptRegistry conceptRegistry;
    private final Map<String, ProfessionEntry> professions = new LinkedHashMap<>();

    /**
     * 构造职业注册表。
     *
     * @param conceptRegistry 概念注册表，提供职业类别的概念数据源
     */
    @Autowired
    public ProfessionRegistry(ConceptRegistry conceptRegistry) {
        this.conceptRegistry = conceptRegistry;
    }

    /**
     * 容器启动后初始化注册表，从概念注册表加载内置职业。
     */
    @PostConstruct
    private void init() { loadFromConcepts(); }

    /**
     * 从 {@link ConceptRegistry} 中读取类别为 {@code PROFESSION} 的概念，
     * 批量构建并注册内置职业条目。
     */
    private void loadFromConcepts() {
        List<ConceptRegistry.ConceptEntry> professionConcepts = conceptRegistry.getByCategory("PROFESSION");
        for (ConceptRegistry.ConceptEntry concept : professionConcepts) {
            professions.put(concept.getName(),
                    new ProfessionEntry(concept.getName(), concept.getDisplayName(), concept.getKeywords(), true));
        }
        log.info("从 ConceptRegistry 加载内置职业: {} 个", professions.size());
    }

    /**
     * 注册一个自定义职业，并同步写入概念注册表（类别为 {@code PROFESSION}）。
     *
     * @param name 职业标识
     * @param displayName 职业展示名称
     * @param keywords 命中关键词（以中文顿号分隔）
     */
    public void register(String name, String displayName, String keywords) {
        professions.put(name, new ProfessionEntry(name, displayName, keywords, false));
        ConceptRegistry.ConceptEntry entry = new ConceptRegistry.ConceptEntry();
        entry.setName(name); entry.setDisplayName(displayName); entry.setKeywords(keywords); entry.setCategory("PROFESSION");
        conceptRegistry.register(entry);
        log.info("注册自定义职业: {} ({})", displayName, name);
    }

    /**
     * 根据文本关键词检测职业类型。
     *
     * <p>优先使用概念注册表的类别命中结果；未命中时退化为本地关键词包含匹配。
     * 均无结果时返回 {@link Profession#OTHER} 兜底。</p>
     *
     * @param text 待检测文本
     * @return 检测到的职业标识
     */
    public String detectByKeywords(String text) {
        if (text == null || text.isEmpty()) return Profession.OTHER;
        List<String> detected = conceptRegistry.detectIdentitiesByCategory(text, "PROFESSION");
        if (!detected.isEmpty()) return detected.get(0);
        String lowerText = text.toLowerCase();
        for (ProfessionEntry pe : professions.values()) {
            if (pe.keywords != null) {
                for (String keyword : pe.keywords.split("、")) {
                    if (lowerText.contains(keyword.toLowerCase())) return pe.name;
                }
            }
        }
        return Profession.OTHER;
    }

    /**
     * 返回职业的展示名称。
     *
     * @param name 职业标识
     * @return 展示名称，不存在时原样返回标识
     */
    public String getDisplayName(String name) { ProfessionEntry pe = professions.get(name); return pe != null ? pe.displayName : name; }

    /**
     * 返回职业的命中关键词。
     *
     * @param name 职业标识
     * @return 关键词字符串，不存在时返回 null
     */
    public String getKeywords(String name) { ProfessionEntry pe = professions.get(name); return pe != null ? pe.keywords : null; }

    /**
     * 返回全部已注册职业（内置 + 自定义）的只读视图。
     *
     * @return 职业标识到职业条目的映射
     */
    public Map<String, ProfessionEntry> getProfessions() { return professions; }

    /** 职业条目，记录单个职业的标识、展示名、命中关键词及来源。
     * @param name        职业标识
     * @param displayName 职业展示名称
     * @param keywords    命中关键词（中文顿号分隔）
     * @param builtin     是否为内置职业
     */
    public record ProfessionEntry(String name, String displayName, String keywords, boolean builtin) {}
}
