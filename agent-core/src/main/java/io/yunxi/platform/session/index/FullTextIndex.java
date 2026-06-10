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

    public FullTextIndex(SessionTagMapper sessionTagMapper) {
        this.sessionTagMapper = sessionTagMapper;
    }

    /** 搜索配置 */
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

    /** 搜索结果 */
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

    /** 全文搜索 */
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

    public List<SearchResult> search(String query) { return search(query, SearchOptions.defaults()); }
    public List<SearchResult> search(String query, int limit) { return search(query, SearchOptions.defaults().limit(limit)); }

    /** 按会话分组搜索结果 */
    public Map<String, List<SearchResult>> searchByConversation(String query, SearchOptions options) {
        List<SearchResult> results = search(query, options);
        return results.stream()
                .collect(Collectors.groupingBy(result -> result.getTag().getConversationId(), Collectors.toList()));
    }

    private double calculateRelevanceScore(SessionTagEntity tag, String query) {
        double score = 0.0;
        score += Math.min(tag.getWeight() / 10.0, 1.0) * 0.3;
        if (tag.getConfidence() != null) score += tag.getConfidence() * 0.2;
        String textMatch = tag.getTagName() + " " + tag.getTagValue();
        score += calculateTextMatchScore(textMatch, query) * 0.4;
        score += calculateTimeDecayScore(tag.getCreatedAt()) * 0.1;
        return Math.max(0.0, Math.min(1.0, score));
    }

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

    private Set<String> extractMatchedKeywords(SessionTagEntity tag, String query) {
        Set<String> matchedKeywords = new HashSet<>();
        String textMatch = (tag.getTagName() + " " + tag.getTagValue()).toLowerCase();
        String[] queryWords = query.toLowerCase().split("\\s+");
        for (String word : queryWords) {
            if (textMatch.contains(word) && word.length() > 1) matchedKeywords.add(word);
        }
        return matchedKeywords;
    }

    private boolean applyFilters(SessionTagEntity tag, SearchOptions options) {
        if (tag.getWeight() < options.getMinWeight()) return false;
        if (!options.isIncludeLowConfidence() && tag.getConfidence() != null && tag.getConfidence() < 0.5) return false;
        if (options.getTagTypes() != null && !options.getTagTypes().isEmpty() && !options.getTagTypes().contains(tag.getTagType())) return false;
        if (options.getUserId() != null && !options.getUserId().equals(tag.getUserId())) return false;
        if (options.getAgentName() != null && !options.getAgentName().equals(tag.getAgentName())) return false;
        return true;
    }

    /** 智能查询优化 */
    public String optimizeQuery(String query) {
        if (query == null || query.isBlank()) return "";
        String cleaned = query.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9\\s]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned.length() < 2 ? query : cleaned;
    }

    /** 获取索引统计信息 */
    public Map<String, Object> getIndexStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("indexType", "MySQL FULLTEXT");
        stats.put("parser", "ngram");
        stats.put("status", "active");
        return stats;
    }
}
