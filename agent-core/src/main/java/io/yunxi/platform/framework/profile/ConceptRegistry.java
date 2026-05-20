package io.yunxi.platform.framework.profile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统一概念注册表
 *
 * <p>
 * 统一管理领域话题检测和身份识别规则。
 * </p>
 *
 * <p>
 * 每个概念可同时用于话题检测（按 domain）和身份识别（按 category）。
 * 配置是"词典"，自动识别是"阅读理解"——没有词典就读不懂，只有词典就没有用户画像。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "concepts")
public class ConceptRegistry {

    /** 从 YAML 加载的概念列表 */
    private List<ConceptEntry> entries = new ArrayList<>();

    /** 按 name 索引 */
    private final Map<String, ConceptEntry> byName = new LinkedHashMap<>();

    /** 按 domain 索引：domain → List<ConceptEntry> */
    private final Map<String, List<ConceptEntry>> byDomain = new LinkedHashMap<>();

    /** 按 category 索引：category → List<ConceptEntry> */
    private final Map<String, List<ConceptEntry>> byCategory = new LinkedHashMap<>();

    /** 预编译的领域 Pattern 缓存：conceptName → List<Pattern> */
    private final Map<String, List<Pattern>> patternCache = new LinkedHashMap<>();

    /** 按 domain 分组的预编译 Pattern：domain → Map<conceptName, List<Pattern>> */
    private final Map<String, Map<String, List<Pattern>>> domainPatternCache = new LinkedHashMap<>();

    /**
     * 设置概念列表并重建索引
     *
     * @param entries 概念列表
     */
    public void setEntries(List<ConceptEntry> entries) {
        this.entries = entries != null ? entries : new ArrayList<>();
        rebuildIndex();
    }

    /**
     * 获取概念列表
     *
     * @return 概念列表
     */
    public List<ConceptEntry> getEntries() {
        return entries;
    }

    private void rebuildIndex() {
        byName.clear();
        byDomain.clear();
        byCategory.clear();
        patternCache.clear();
        domainPatternCache.clear();

        for (ConceptEntry entry : this.entries) {
            // byName 索引
            byName.put(entry.getName(), entry);

            // byDomain 索引
            if (entry.getDomain() != null && !entry.getDomain().isEmpty()) {
                byDomain.computeIfAbsent(entry.getDomain(), k -> new ArrayList<>()).add(entry);
            }

            // byCategory 索引
            if (entry.getCategory() != null && !entry.getCategory().isEmpty()) {
                byCategory.computeIfAbsent(entry.getCategory(), k -> new ArrayList<>()).add(entry);
            }

            // 预编译 Pattern
            List<Pattern> patterns = compileKeywords(entry.getKeywords());
            if (!patterns.isEmpty()) {
                patternCache.put(entry.getName(), patterns);

                // domain pattern 缓存
                if (entry.getDomain() != null && !entry.getDomain().isEmpty()) {
                    domainPatternCache
                            .computeIfAbsent(entry.getDomain(), k -> new LinkedHashMap<>())
                            .put(entry.getName(), patterns);
                }
            }
        }

        log.info("ConceptRegistry 索引构建完成: {} 个概念, {} 个领域, {} 个类别",
                byName.size(), byDomain.size(), byCategory.size());
    }

    /**
     * 将顿号/逗号分隔的关键词编译为 Pattern 列表
     * 支持两种分隔符："、"（顿号）和 ","（逗号），以及 "|"（管道符）
     */
    private List<Pattern> compileKeywords(String keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        // 统一替换分隔符后按逗号分割
        String normalized = keywords.replace("、", ",").replace("|", ",");
        return Arrays.stream(normalized.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> Pattern.compile("(" + Pattern.quote(s) + ")"))
                .toList();
    }

    // ==================== 话题检测（由问题内容决定） ====================

    /**
     * 检测文本匹配的领域及得分
     *
     * @return domain → score 映射，score = 匹配的概念数 / 该领域总概念数
     */
    public Map<String, Double> detectDomains(String text) {
        if (text == null || text.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> scores = new HashMap<>();
        for (Map.Entry<String, Map<String, List<Pattern>>> domainEntry : domainPatternCache.entrySet()) {
            String domain = domainEntry.getKey();
            int matchedConcepts = 0;
            int totalConcepts = domainEntry.getValue().size();

            for (Map.Entry<String, List<Pattern>> conceptEntry : domainEntry.getValue().entrySet()) {
                for (Pattern pattern : conceptEntry.getValue()) {
                    if (pattern.matcher(text).find()) {
                        matchedConcepts++;
                        break; // 一个概念只要有一个 keyword 匹配即可
                    }
                }
            }

            if (matchedConcepts > 0) {
                scores.put(domain, (double) matchedConcepts / totalConcepts);
            }
        }

        return scores;
    }

    /**
     * 获取领域对应的预编译 Pattern（供 TaskAnalyzer 使用）
     *
     * @return domain → List<Pattern> 映射
     */
    public Map<String, List<Pattern>> getDomainPatterns() {
        // 将 domainPatternCache 展平为 domain → 合并的 Pattern 列表
        Map<String, List<Pattern>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<Pattern>>> domainEntry : domainPatternCache.entrySet()) {
            List<Pattern> allPatterns = domainEntry.getValue().values().stream()
                    .flatMap(List::stream)
                    .toList();
            result.put(domainEntry.getKey(), allPatterns);
        }
        return result;
    }

    // ==================== 身份识别（由关键词匹配） ====================

    /**
     * 检测文本匹配的身份概念
     *
     * @return 匹配的 concept name 列表（按匹配度排序）
     */
    public List<String> detectIdentities(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> matchCounts = new LinkedHashMap<>();
        for (Map.Entry<String, List<Pattern>> entry : patternCache.entrySet()) {
            int count = 0;
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(text).find()) {
                    count++;
                }
            }
            if (count > 0) {
                matchCounts.put(entry.getKey(), count);
            }
        }

        return matchCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 检测文本匹配的指定类别的身份概念
     *
     * @param text     待检测文本
     * @param category 类别
     * @return 匹配的概念名称列表
     */
    public List<String> detectIdentitiesByCategory(String text, String category) {
        List<String> allDetected = detectIdentities(text);
        Set<String> categoryNames = byCategory.getOrDefault(category, List.of())
                .stream().map(ConceptEntry::getName).collect(Collectors.toSet());
        return allDetected.stream()
                .filter(categoryNames::contains)
                .toList();
    }

    // ==================== 查询方法 ====================

    /**
     * 根据名称获取概念
     *
     * @param name 概念名称
     * @return 概念条目
     */
    public ConceptEntry getByName(String name) {
        return byName.get(name);
    }

    /**
     * 获取概念显示名称
     *
     * @param name 概念名称
     * @return 显示名称
     */
    public String getDisplayName(String name) {
        ConceptEntry entry = byName.get(name);
        return entry != null ? entry.getDisplayName() : name;
    }

    /**
     * 获取概念所属领域
     *
     * @param name 概念名称
     * @return 领域标识
     */
    public String getDomain(String name) {
        ConceptEntry entry = byName.get(name);
        return entry != null ? entry.getDomain() : null;
    }

    /**
     * 获取概念所属类别
     *
     * @param name 概念名称
     * @return 类别标识
     */
    public String getCategory(String name) {
        ConceptEntry entry = byName.get(name);
        return entry != null ? entry.getCategory() : null;
    }

    /**
     * 根据领域获取概念列表
     *
     * @param domain 领域标识
     * @return 概念列表
     */
    public List<ConceptEntry> getByDomain(String domain) {
        return byDomain.getOrDefault(domain, List.of());
    }

    /**
     * 根据类别获取概念列表
     *
     * @param category 类别标识
     * @return 概念列表
     */
    public List<ConceptEntry> getByCategory(String category) {
        return byCategory.getOrDefault(category, List.of());
    }

    /**
     * 获取所有概念的名称映射
     *
     * @return 不可变的名称到概念的映射
     */
    public Map<String, ConceptEntry> getAllByName() {
        return Collections.unmodifiableMap(byName);
    }

    /**
     * 注册自定义概念（业务层运行时调用）
     *
     * @param entry 概念条目
     */
    public void register(ConceptEntry entry) {
        entries.add(entry);
        // 增量更新索引
        byName.put(entry.getName(), entry);
        if (entry.getDomain() != null && !entry.getDomain().isEmpty()) {
            byDomain.computeIfAbsent(entry.getDomain(), k -> new ArrayList<>()).add(entry);
        }
        if (entry.getCategory() != null && !entry.getCategory().isEmpty()) {
            byCategory.computeIfAbsent(entry.getCategory(), k -> new ArrayList<>()).add(entry);
        }
        List<Pattern> patterns = compileKeywords(entry.getKeywords());
        if (!patterns.isEmpty()) {
            patternCache.put(entry.getName(), patterns);
            if (entry.getDomain() != null && !entry.getDomain().isEmpty()) {
                domainPatternCache
                        .computeIfAbsent(entry.getDomain(), k -> new LinkedHashMap<>())
                        .put(entry.getName(), patterns);
            }
        }
        log.info("注册自定义概念: {} ({})", entry.getDisplayName(), entry.getName());
    }

    /**
     * 概念配置条目
     */
    public static class ConceptEntry {

        /** 概念标识，如 MEDICAL, PARENTING, SCHOOL_MEAL */
        private String name;

        /** 对话领域，如 health, family, school-meal */
        private String domain;

        /** 显示名称，如 "医疗健康" */
        private String displayName;

        /** 识别关键词（顿号/逗号/管道符分隔） */
        private String keywords;

        /** 分类：PROFESSION / ROLE / INTEREST / PRODUCT / DEPARTMENT / R_AND_D */
        private String category;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getKeywords() {
            return keywords;
        }

        public void setKeywords(String keywords) {
            this.keywords = keywords;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }
    }
}
