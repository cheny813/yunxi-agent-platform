package io.yunxi.platform.session.index;

import io.yunxi.platform.session.entity.SessionTagEntity;
import io.yunxi.platform.session.mapper.SessionTagMapper;
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

    /** 构造全文索引服务。
     * @param sessionTagMapper 会话标签 Mapper（提供 FULLTEXT 检索能力）
     */
    public FullTextIndex(SessionTagMapper sessionTagMapper) {
        this.sessionTagMapper = sessionTagMapper;
    }

    /** 搜索配置项，封装分页、权重、置信度、标签类型、用户与 Agent 等多维过滤条件，并支持链式构造。 */
    @lombok.Data
    public static class SearchOptions {
        private int limit = 50;
        private int minWeight = 0;
        private boolean includeLowConfidence = false;
        private Set<String> tagTypes = null;
        private String userId = null;
        private String agentName = null;

        public static SearchOptions defaults() { return new SearchOptions(); }
        public SearchOptions limit(int limit) { this.limit = limit; return this; }
        public SearchOptions minWeight(int minWeight) { this.minWeight = minWeight; return this; }
        public SearchOptions includeLowConfidence(boolean include) { this.includeLowConfidence = include; return this; }
        public SearchOptions tagTypes(String... types) { this.tagTypes = types != null ? Set.of(types) : null; return this; }
        public SearchOptions userId(String userId) { this.userId = userId; return this; }
        public SearchOptions agentName(String agentName) { this.agentName = agentName; return this; }
    }

    /** 搜索结果条目，封装命中的标签实体、相关性得分与匹配关键词。 */
    @lombok.Data
    public static class SearchResult {
        private SessionTagEntity tag;
        private double relevanceScore;
        private Set<String> matchedKeywords;

        public SearchResult(SessionTagEntity tag, double relevanceScore, Set<String> matchedKeywords) {
            this.tag = tag;
            this.relevanceScore = relevanceScore;
            this.matchedKeywords = matchedKeywords;
        }
    }

    /**
     * 执行全文搜索，按搜索选项过滤候选标签并计算相关性得分后排序返回。
     *
     * @param query   查询文本
     * @param options 搜索选项（为 null 时使用默认配置）
     * @return 相关性排序后的搜索结果列表；查询为空或异常时返回空列表
     */
    public List<SearchResult> search(String query, SearchOptions options) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        final SearchOptions effectiveOptions = options != null ? options : SearchOptions.defaults();
        try {
            List<SessionTagEntity> rawResults = sessionTagMapper.fullTextSearch(query, effectiveOptions.getLimit());
            List<SessionTagEntity> filteredResults = rawResults.stream()
                    .filter(tag -> applyFilters(tag, effectiveOptions))
                    .collect(Collectors.toList());
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
     * 执行全文搜索（使用默认选项）。
     *
     * @param query 查询文本
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String query) { return search(query, SearchOptions.defaults()); }

    /**
     * 执行全文搜索（指定返回条数上限）。
     *
     * @param query 查询文本
     * @param limit 返回条数上限
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String query, int limit) { return search(query, SearchOptions.defaults().limit(limit)); }

    /**
     * 按会话（conversationId）对搜索结果分组。
     *
     * @param query   查询文本
     * @param options 搜索选项
     * @return 会话ID到该会话下搜索结果列表的映射
     */
    public Map<String, List<SearchResult>> searchByConversation(String query, SearchOptions options) {
        List<SearchResult> results = search(query, options);
        return results.stream()
                .collect(Collectors.groupingBy(result -> result.getTag().getConversationId(), Collectors.toList()));
    }

    /**
     * 计算标签与查询的整体相关性得分（0~1）。
     *
     * <p>权重占比 0.3、置信度 0.2、文本匹配 0.4、时间衰减 0.1，最后裁剪到 [0,1]。</p>
     *
     * @param tag 候选标签实体
     * @param query 查询文本
     * @return 相关性得分
     */
    private double calculateRelevanceScore(SessionTagEntity tag, String query) {
        double score = 0.0;
        score += Math.min(tag.getWeight() / 10.0, 1.0) * 0.3;
        if (tag.getConfidence() != null) score += tag.getConfidence() * 0.2;
        String textMatch = tag.getTagName() + " " + tag.getTagValue();
        score += calculateTextMatchScore(textMatch, query) * 0.4;
        score += calculateTimeDecayScore(tag.getCreatedAt()) * 0.1;
        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * 计算查询词在文本中的命中比例。
     *
     * @param text 待匹配文本（标签名+值）
     * @param query 查询文本
     * @return 命中词数占查询词数的比例
     */
    private double calculateTextMatchScore(String text, String query) {
        if (text == null || text.isBlank()) return 0.0;
        String lowerText = text.toLowerCase();
        String lowerQuery = query.toLowerCase();
        String[] queryWords = lowerQuery.split("\\s+");
        int matchCount = 0;
        for (String word : queryWords) {
            if (lowerText.contains(word)) matchCount++;
        }
        return (double) matchCount / queryWords.length;
    }

    /**
     * 根据标签创建时间计算时间衰减得分（越新越高）。
     *
     * @param createdAt 标签创建时间（null 时返回 0.5）
     * @return 时间衰减得分
     */
    private double calculateTimeDecayScore(java.time.LocalDateTime createdAt) {
        if (createdAt == null) return 0.5;
        java.time.Duration age = java.time.Duration.between(createdAt, java.time.LocalDateTime.now());
        long daysOld = age.toDays();
        if (daysOld <= 1) return 1.0;
        else if (daysOld <= 7) return 0.8;
        else if (daysOld <= 30) return 0.6;
        else if (daysOld <= 90) return 0.4;
        else return 0.2;
    }

    /**
     * 提取查询中实际命中标签文本的关键词集合。
     *
     * @param tag 候选标签实体
     * @param query 查询文本
     * @return 命中的关键词集合
     */
    private Set<String> extractMatchedKeywords(SessionTagEntity tag, String query) {
        Set<String> matchedKeywords = new HashSet<>();
        String textMatch = (tag.getTagName() + " " + tag.getTagValue()).toLowerCase();
        String[] queryWords = query.toLowerCase().split("\\s+");
        for (String word : queryWords) {
            if (textMatch.contains(word) && word.length() > 1) matchedKeywords.add(word);
        }
        return matchedKeywords;
    }

    /**
     * 按搜索选项过滤候选标签（权重、置信度、类型、用户、Agent）。
     *
     * @param tag 候选标签实体
     * @param options 搜索选项
     * @return 通过过滤返回 true
     */
    private boolean applyFilters(SessionTagEntity tag, SearchOptions options) {
        if (tag.getWeight() < options.getMinWeight()) return false;
        if (!options.isIncludeLowConfidence() && tag.getConfidence() != null && tag.getConfidence() < 0.5) return false;
        if (options.getTagTypes() != null && !options.getTagTypes().isEmpty() && !options.getTagTypes().contains(tag.getTagType())) return false;
        if (options.getUserId() != null && !options.getUserId().equals(tag.getUserId())) return false;
        if (options.getAgentName() != null && !options.getAgentName().equals(tag.getAgentName())) return false;
        return true;
    }

    /**
     * 智能优化查询文本，去除非中英文数字字符并合并空格。
     *
     * @param query 原始查询文本
     * @return 清理后的查询；长度不足 2 字符时原样返回
     */
    public String optimizeQuery(String query) {
        if (query == null || query.isBlank()) return "";
        String cleaned = query.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9\\s]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned.length() < 2 ? query : cleaned;
    }

    /**
     * 获取全文索引的统计信息（索引类型、分词器、状态）。
     *
     * @return 统计信息映射
     */
    public Map<String, Object> getIndexStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("indexType", "MySQL FULLTEXT");
        stats.put("parser", "ngram");
        stats.put("status", "active");
        return stats;
    }
}
