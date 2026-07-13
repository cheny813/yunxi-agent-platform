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
 * @version 2.0.0
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

    /**
     * 设置概念条目列表并重建索引。
     *
     * @param entries 概念条目集合，为 null 时退化为空列表
     */
    public void setEntries(List<ConceptEntry> entries) {
        this.entries = entries != null ? entries : new ArrayList<>();
        rebuildIndex();
    }

    /**
     * 返回当前配置的概念条目列表。
     *
     * @return 概念条目集合
     */
    public List<ConceptEntry> getEntries() { return entries; }

    /**
     * 依据当前 {@code entries} 重建全部索引（按名称/领域/类别）与关键词正则缓存。
     * 调用前会清空既有索引，确保与最新配置一致。
     */
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

    /**
     * 将关键词字符串编译为正则模式列表。
     *
     * <p>支持以中文顿号、逗号、竖线分隔，并对每个关键词做精确转义后包装为正则。</p>
     *
     * @param keywords 以分隔符连接的关键词串
     * @return 编译后的正则模式列表，无关键词时返回空列表
     */
    private List<Pattern> compileKeywords(String keywords) {
        if (keywords == null || keywords.isEmpty()) return List.of();
        String normalized = keywords.replace("、", ",").replace("|", ",");
        return Arrays.stream(normalized.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> Pattern.compile("(" + Pattern.quote(s) + ")"))
                .toList();
    }

    /**
     * 检测文本命中的领域及其匹配度评分。
     *
     * <p>对每个领域统计其下被命中的概念占比，作为该领域的得分。</p>
     *
     * @param text 待检测文本
     * @return 领域到得分（0~1）的映射，文本为空时返回空映射
     */
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

    /**
     * 返回所有领域的概念关键词正则（同一领域内多概念的正则合并为列表）。
     *
     * @return 领域到正则列表的映射
     */
    public Map<String, List<Pattern>> getDomainPatterns() {
        Map<String, List<Pattern>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<Pattern>>> domainEntry : domainPatternCache.entrySet()) {
            result.put(domainEntry.getKey(), domainEntry.getValue().values().stream().flatMap(List::stream).toList());
        }
        return result;
    }

    /**
     * 检测文本命中的身份概念，按命中次数降序返回。
     *
     * @param text 待检测文本
     * @return 身份概念名称列表，文本为空时返回空列表
     */
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

    /**
     * 在指定类别范围内检测文本命中的身份概念。
     *
     * @param text 待检测文本
     * @param category 限定的概念类别
     * @return 命中且属于该类别的身份概念名称列表
     */
    public List<String> detectIdentitiesByCategory(String text, String category) {
        List<String> allDetected = detectIdentities(text);
        Set<String> categoryNames = byCategory.getOrDefault(category, List.of())
                .stream().map(ConceptEntry::getName).collect(Collectors.toSet());
        return allDetected.stream().filter(categoryNames::contains).toList();
    }

    /**
     * 按名称查询概念条目。
     *
     * @param name 概念名称
     * @return 对应的概念条目，不存在时返回 null
     */
    public ConceptEntry getByName(String name) { return byName.get(name); }

    /**
     * 返回概念的展示名称。
     *
     * @param name 概念名称
     * @return 展示名称，不存在时原样返回名称
     */
    public String getDisplayName(String name) { ConceptEntry e = byName.get(name); return e != null ? e.getDisplayName() : name; }

    /**
     * 返回概念所属领域。
     *
     * @param name 概念名称
     * @return 领域标识，不存在或无领域时返回 null
     */
    public String getDomain(String name) { ConceptEntry e = byName.get(name); return e != null ? e.getDomain() : null; }

    /**
     * 返回概念所属类别。
     *
     * @param name 概念名称
     * @return 类别标识，不存在或无类别时返回 null
     */
    public String getCategory(String name) { ConceptEntry e = byName.get(name); return e != null ? e.getCategory() : null; }

    /**
     * 返回指定领域下的全部概念。
     *
     * @param domain 领域标识
     * @return 概念列表，领域不存在时返回空列表
     */
    public List<ConceptEntry> getByDomain(String domain) { return byDomain.getOrDefault(domain, List.of()); }

    /**
     * 返回指定类别下的全部概念。
     *
     * @param category 类别标识
     * @return 概念列表，类别不存在时返回空列表
     */
    public List<ConceptEntry> getByCategory(String category) { return byCategory.getOrDefault(category, List.of()); }

    /**
     * 返回全部概念（按名称索引的不可变视图）。
     *
     * @return 名称到概念条目的只读映射
     */
    public Map<String, ConceptEntry> getAllByName() { return Collections.unmodifiableMap(byName); }

    /**
     * 运行时注册一个自定义概念，并同步更新索引与正则缓存。
     *
     * @param entry 待注册的概念条目
     */
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

    /**
     * 概念条目，承载单个领域概念的名称、领域、类别与关键词配置。
     */
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
