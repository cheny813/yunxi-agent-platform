package io.yunxi.platform.agent.profile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统一概念注册表
 * <p>统一管理领域话题检测和身份识别规则。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "concepts")
public class ConceptRegistry {

    private List<ConceptEntry> entries = new ArrayList<>();
    private final Map<String, ConceptEntry> byName = new LinkedHashMap<>();
    private final Map<String, List<ConceptEntry>> byDomain = new LinkedHashMap<>();
    private final Map<String, List<ConceptEntry>> byCategory = new LinkedHashMap<>();
    private final Map<String, List<Pattern>> patternCache = new LinkedHashMap<>();
    private final Map<String, Map<String, List<Pattern>>> domainPatternCache = new LinkedHashMap<>();

    public void setEntries(List<ConceptEntry> entries) {
        this.entries = entries != null ? entries : new ArrayList<>();
        rebuildIndex();
    }

    public List<ConceptEntry> getEntries() { return entries; }

    private void rebuildIndex() {
        byName.clear(); byDomain.clear(); byCategory.clear();
        patternCache.clear(); domainPatternCache.clear();
        for (ConceptEntry entry : this.entries) {
            byName.put(entry.getName(), entry);
            if (entry.getDomain() != null && !entry.getDomain().isEmpty())
                byDomain.computeIfAbsent(entry.getDomain(), k -> new ArrayList<>()).add(entry);
            if (entry.getCategory() != null && !entry.getCategory().isEmpty())
                byCategory.computeIfAbsent(entry.getCategory(), k -> new ArrayList<>()).add(entry);
            List<Pattern> patterns = compileKeywords(entry.getKeywords());
            if (!patterns.isEmpty()) {
                patternCache.put(entry.getName(), patterns);
                if (entry.getDomain() != null && !entry.getDomain().isEmpty())
                    domainPatternCache.computeIfAbsent(entry.getDomain(), k -> new LinkedHashMap<>()).put(entry.getName(), patterns);
            }
        }
        log.info("ConceptRegistry 索引构建完成: {} 个概念, {} 个领域, {} 个类别", byName.size(), byDomain.size(), byCategory.size());
    }

    private List<Pattern> compileKeywords(String keywords) {
        if (keywords == null || keywords.isEmpty()) return List.of();
        String normalized = keywords.replace("、", ",").replace("|", ",");
        return Arrays.stream(normalized.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> Pattern.compile("(" + Pattern.quote(s) + ")"))
                .toList();
    }

    public Map<String, Double> detectDomains(String text) {
        if (text == null || text.isEmpty()) return Map.of();
        Map<String, Double> scores = new HashMap<>();
        for (Map.Entry<String, Map<String, List<Pattern>>> domainEntry : domainPatternCache.entrySet()) {
            String domain = domainEntry.getKey();
            int matchedConcepts = 0;
            int totalConcepts = domainEntry.getValue().size();
            for (Map.Entry<String, List<Pattern>> conceptEntry : domainEntry.getValue().entrySet()) {
                for (Pattern pattern : conceptEntry.getValue()) {
                    if (pattern.matcher(text).find()) { matchedConcepts++; break; }
                }
            }
            if (matchedConcepts > 0) scores.put(domain, (double) matchedConcepts / totalConcepts);
        }
        return scores;
    }

    public Map<String, List<Pattern>> getDomainPatterns() {
        Map<String, List<Pattern>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<Pattern>>> domainEntry : domainPatternCache.entrySet()) {
            result.put(domainEntry.getKey(), domainEntry.getValue().values().stream().flatMap(List::stream).toList());
        }
        return result;
    }

    public List<String> detectIdentities(String text) {
        if (text == null || text.isEmpty()) return List.of();
        Map<String, Integer> matchCounts = new LinkedHashMap<>();
        for (Map.Entry<String, List<Pattern>> entry : patternCache.entrySet()) {
            int count = 0;
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(text).find()) count++;
            }
            if (count > 0) matchCounts.put(entry.getKey(), count);
        }
        return matchCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
    }

    public List<String> detectIdentitiesByCategory(String text, String category) {
        List<String> allDetected = detectIdentities(text);
        Set<String> categoryNames = byCategory.getOrDefault(category, List.of())
                .stream().map(ConceptEntry::getName).collect(Collectors.toSet());
        return allDetected.stream().filter(categoryNames::contains).toList();
    }

    public ConceptEntry getByName(String name) { return byName.get(name); }
    public String getDisplayName(String name) { ConceptEntry e = byName.get(name); return e != null ? e.getDisplayName() : name; }
    public String getDomain(String name) { ConceptEntry e = byName.get(name); return e != null ? e.getDomain() : null; }
    public String getCategory(String name) { ConceptEntry e = byName.get(name); return e != null ? e.getCategory() : null; }
    public List<ConceptEntry> getByDomain(String domain) { return byDomain.getOrDefault(domain, List.of()); }
    public List<ConceptEntry> getByCategory(String category) { return byCategory.getOrDefault(category, List.of()); }
    public Map<String, ConceptEntry> getAllByName() { return Collections.unmodifiableMap(byName); }

    public void register(ConceptEntry entry) {
        entries.add(entry);
        byName.put(entry.getName(), entry);
        if (entry.getDomain() != null && !entry.getDomain().isEmpty())
            byDomain.computeIfAbsent(entry.getDomain(), k -> new ArrayList<>()).add(entry);
        if (entry.getCategory() != null && !entry.getCategory().isEmpty())
            byCategory.computeIfAbsent(entry.getCategory(), k -> new ArrayList<>()).add(entry);
        List<Pattern> patterns = compileKeywords(entry.getKeywords());
        if (!patterns.isEmpty()) {
            patternCache.put(entry.getName(), patterns);
            if (entry.getDomain() != null && !entry.getDomain().isEmpty())
                domainPatternCache.computeIfAbsent(entry.getDomain(), k -> new LinkedHashMap<>()).put(entry.getName(), patterns);
        }
        log.info("注册自定义概念: {} ({})", entry.getDisplayName(), entry.getName());
    }

    public static class ConceptEntry {
        private String name;
        private String domain;
        private String displayName;
        private String keywords;
        private String category;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getKeywords() { return keywords; }
        public void setKeywords(String keywords) { this.keywords = keywords; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }
}
