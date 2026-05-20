package io.yunxi.platform.framework.session.index;

import io.yunxi.platform.framework.session.entity.SessionTagEntity;
import io.yunxi.platform.framework.session.mapper.SessionTagMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 全文索引服务
 * <p>
 * 基于MySQL FULLTEXT索引实现全文搜索功能
 * 支持中文分词（ngram parser）和相关性排序
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class FullTextIndex {

    /** 会话标签 Mapper */
    private final SessionTagMapper sessionTagMapper;

    /**
     * 构造全文索引服务
     *
     * @param sessionTagMapper 会话标签 Mapper
     */
    public FullTextIndex(SessionTagMapper sessionTagMapper) {
        this.sessionTagMapper = sessionTagMapper;
    }

    /**
     * 搜索配置
     */
    @lombok.Data
    public static class SearchOptions {
        /**
         * 最大返回结果数
         */
        private int limit = 50;

        /**
         * 最小权重阈值
         */
        private int minWeight = 0;

        /**
         * 是否包含低置信度结果
         */
        private boolean includeLowConfidence = false;

        /**
         * 标签类型过滤
         */
        private Set<String> tagTypes = null;

        /**
         * 用户ID过滤
         */
        private String userId = null;

        /**
         * Agent名称过滤
         */
        private String agentName = null;

        public static SearchOptions defaults() {
            return new SearchOptions();
        }

        public SearchOptions limit(int limit) {
            this.limit = limit;
            return this;
        }

        public SearchOptions minWeight(int minWeight) {
            this.minWeight = minWeight;
            return this;
        }

        public SearchOptions includeLowConfidence(boolean include) {
            this.includeLowConfidence = include;
            return this;
        }

        public SearchOptions tagTypes(String... types) {
            this.tagTypes = types != null ? Set.of(types) : null;
            return this;
        }

        public SearchOptions userId(String userId) {
            this.userId = userId;
            return this;
        }

        public SearchOptions agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }
    }

    /**
     * 搜索结果
     */
    @lombok.Data
    public static class SearchResult {
        /**
         * 匹配的标签
         */
        private SessionTagEntity tag;

        /**
         * 相关性评分（0-1）
         */
        private double relevanceScore;

        /**
         * 匹配的关键词
         */
        private Set<String> matchedKeywords;

        public SearchResult(SessionTagEntity tag, double relevanceScore, Set<String> matchedKeywords) {
            this.tag = tag;
            this.relevanceScore = relevanceScore;
            this.matchedKeywords = matchedKeywords;
        }
    }

    /**
     * 全文搜索
     *
     * @param query  搜索查询
     * @param options 搜索选项
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String query, SearchOptions options) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        final SearchOptions effectiveOptions = options != null ? options : SearchOptions.defaults();

        try {
            // 执行MySQL全文搜索
            List<SessionTagEntity> rawResults = sessionTagMapper.fullTextSearch(query, effectiveOptions.getLimit());

            // 应用过滤条件
            List<SessionTagEntity> filteredResults = rawResults.stream()
                    .filter(tag -> applyFilters(tag, effectiveOptions))
                    .collect(Collectors.toList());

            // 计算相关性评分并构建搜索结果
            List<SearchResult> results = filteredResults.stream()
                    .map(tag -> {
                        double relevanceScore = calculateRelevanceScore(tag, query);
                        Set<String> matchedKeywords = extractMatchedKeywords(tag, query);
                        return new SearchResult(tag, relevanceScore, matchedKeywords);
                    })
                    .sorted(Comparator.comparingDouble(SearchResult::getRelevanceScore).reversed())
                    .limit(effectiveOptions.getLimit())
                    .collect(Collectors.toList());

            log.debug("Full-text search for '{}' returned {} results", query, results.size());
            return results;
        } catch (Exception e) {
            log.error("Full-text search failed for query: {}", query, e);
            return Collections.emptyList();
        }
    }

    /**
     * 全文搜索（简化版）
     *
     * @param query 搜索查询
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String query) {
        return search(query, SearchOptions.defaults());
    }

    /**
     * 全文搜索（限制结果数）
     *
     * @param query 搜索查询
     * @param limit 最大返回结果数
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String query, int limit) {
        return search(query, SearchOptions.defaults().limit(limit));
    }

    /**
     * 按会话分组搜索结果
     *
     * @param query 搜索查询
     * @param options 搜索选项
     * @return 按会话ID分组的搜索结果
     */
    public Map<String, List<SearchResult>> searchByConversation(String query, SearchOptions options) {
        List<SearchResult> results = search(query, options);

        return results.stream()
                .collect(Collectors.groupingBy(
                        result -> result.getTag().getConversationId(),
                        Collectors.toList()
                ));
    }

    /**
     * 计算相关性评分
     *
     * @param tag  标签实体
     * @param query 搜索查询
     * @return 相关性评分（0-1）
     */
    private double calculateRelevanceScore(SessionTagEntity tag, String query) {
        double score = 0.0;

        // 1. 权重贡献（30%）
        score += Math.min(tag.getWeight() / 10.0, 1.0) * 0.3;

        // 2. 置信度贡献（20%）
        if (tag.getConfidence() != null) {
            score += tag.getConfidence() * 0.2;
        }

        // 3. 文本匹配度（40%）
        String textMatch = tag.getTagName() + " " + tag.getTagValue();
        score += calculateTextMatchScore(textMatch, query) * 0.4;

        // 4. 时间衰减（10%）- 较新的内容得分更高
        score += calculateTimeDecayScore(tag.getCreatedAt()) * 0.1;

        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * 计算文本匹配评分
     *
     * @param text  文本内容
     * @param query 搜索查询
     * @return 匹配评分（0-1）
     */
    private double calculateTextMatchScore(String text, String query) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        String lowerText = text.toLowerCase();
        String lowerQuery = query.toLowerCase();
        String[] queryWords = lowerQuery.split("\\s+");

        int matchCount = 0;
        for (String word : queryWords) {
            if (lowerText.contains(word)) {
                matchCount++;
            }
        }

        return (double) matchCount / queryWords.length;
    }

    /**
     * 计算时间衰减评分
     *
     * @param createdAt 创建时间
     * @return 时间衰减评分（0-1）
     */
    private double calculateTimeDecayScore(java.time.LocalDateTime createdAt) {
        if (createdAt == null) {
            return 0.5;
        }

        java.time.Duration age = java.time.Duration.between(
                createdAt,
                java.time.LocalDateTime.now()
        );

        // 7天内的内容得分较高
        long daysOld = age.toDays();
        if (daysOld <= 1) {
            return 1.0;
        } else if (daysOld <= 7) {
            return 0.8;
        } else if (daysOld <= 30) {
            return 0.6;
        } else if (daysOld <= 90) {
            return 0.4;
        } else {
            return 0.2;
        }
    }

    /**
     * 提取匹配的关键词
     *
     * @param tag   标签实体
     * @param query 搜索查询
     * @return 匹配的关键词集合
     */
    private Set<String> extractMatchedKeywords(SessionTagEntity tag, String query) {
        Set<String> matchedKeywords = new HashSet<>();

        String textMatch = (tag.getTagName() + " " + tag.getTagValue()).toLowerCase();
        String[] queryWords = query.toLowerCase().split("\\s+");

        for (String word : queryWords) {
            if (textMatch.contains(word) && word.length() > 1) {
                matchedKeywords.add(word);
            }
        }

        return matchedKeywords;
    }

    /**
     * 应用过滤条件
     *
     * @param tag     标签实体
     * @param options 搜索选项
     * @return 是否通过过滤
     */
    private boolean applyFilters(SessionTagEntity tag, SearchOptions options) {
        // 权重过滤
        if (tag.getWeight() < options.getMinWeight()) {
            return false;
        }

        // 置信度过滤
        if (!options.isIncludeLowConfidence() &&
                tag.getConfidence() != null &&
                tag.getConfidence() < 0.5) {
            return false;
        }

        // 标签类型过滤
        if (options.getTagTypes() != null &&
                !options.getTagTypes().isEmpty() &&
                !options.getTagTypes().contains(tag.getTagType())) {
            return false;
        }

        // 用户ID过滤
        if (options.getUserId() != null &&
                !options.getUserId().equals(tag.getUserId())) {
            return false;
        }

        // Agent名称过滤
        if (options.getAgentName() != null &&
                !options.getAgentName().equals(tag.getAgentName())) {
            return false;
        }

        return true;
    }

    /**
     * 智能查询优化
     * <p>
     * 清理和优化搜索查询
     * </p>
     *
     * @param query 原始查询
     * @return 优化后的查询
     */
    public String optimizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        // 移除特殊字符
        String cleaned = query.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9\\s]", " ");

        // 压缩空白
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        // 如果查询太短，返回原查询
        if (cleaned.length() < 2) {
            return query;
        }

        return cleaned;
    }

    /**
     * 获取索引统计信息
     *
     * @return 统计信息
     */
    public Map<String, Object> getIndexStats() {
        Map<String, Object> stats = new HashMap<>();
        // 这里可以添加索引统计逻辑
        // 例如：索引大小、文档数量等
        stats.put("indexType", "MySQL FULLTEXT");
        stats.put("parser", "ngram");
        stats.put("status", "active");
        return stats;
    }
}
