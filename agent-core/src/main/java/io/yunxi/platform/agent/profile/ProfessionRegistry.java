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
 * @version 3.0.0
 */
@Slf4j
@Component
public class ProfessionRegistry {

    private final ConceptRegistry conceptRegistry;
    private final Map<String, ProfessionEntry> professions = new LinkedHashMap<>();

    @Autowired
    public ProfessionRegistry(ConceptRegistry conceptRegistry) {
        this.conceptRegistry = conceptRegistry;
    }

    @PostConstruct
    private void init() { loadFromConcepts(); }

    private void loadFromConcepts() {
        List<ConceptRegistry.ConceptEntry> professionConcepts = conceptRegistry.getByCategory("PROFESSION");
        for (ConceptRegistry.ConceptEntry concept : professionConcepts) {
            professions.put(concept.getName(),
                    new ProfessionEntry(concept.getName(), concept.getDisplayName(), concept.getKeywords(), true));
        }
        log.info("从 ConceptRegistry 加载内置职业: {} 个", professions.size());
    }

    public void register(String name, String displayName, String keywords) {
        professions.put(name, new ProfessionEntry(name, displayName, keywords, false));
        ConceptRegistry.ConceptEntry entry = new ConceptRegistry.ConceptEntry();
        entry.setName(name); entry.setDisplayName(displayName); entry.setKeywords(keywords); entry.setCategory("PROFESSION");
        conceptRegistry.register(entry);
        log.info("注册自定义职业: {} ({})", displayName, name);
    }

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

    public String getDisplayName(String name) { ProfessionEntry pe = professions.get(name); return pe != null ? pe.displayName : name; }
    public String getKeywords(String name) { ProfessionEntry pe = professions.get(name); return pe != null ? pe.keywords : null; }
    public Map<String, ProfessionEntry> getProfessions() { return professions; }

    public record ProfessionEntry(String name, String displayName, String keywords, boolean builtin) {}
}
