package io.yunxi.platform.session.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.session.entity.SessionSummaryEntity;
import io.yunxi.platform.session.entity.SessionTagEntity;
import io.yunxi.platform.session.index.FullTextIndex;
import io.yunxi.platform.intelligent.config.IntelligentProperties;
import io.yunxi.platform.intelligent.llm.IntelligentLlmService;
import io.yunxi.platform.shared.entity.ConversationEntity;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 会话搜索服务
 * <p>
 * 提供会话搜索、摘要生成和上下文恢复功? * 集成ㄦ搜索和LLM摘生成
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class SessionSearchService {

    /** 会话数据库服?*/
    private final SessionDatabaseService sessionDatabaseService;
    /** 鍏ㄦ枃绱㈠服务 */
    private final FullTextIndex fullTextIndex;
    /** 智能LLM服务 */
    private final IntelligentLlmService llmService;
    /** 智能配置属€?*/
    private final IntelligentProperties intelligentProperties;

    public SessionSearchService(SessionDatabaseService sessionDatabaseService,
            FullTextIndex fullTextIndex,
            IntelligentLlmService llmService,
            IntelligentProperties intelligentProperties,
            ObjectMapper objectMapper) {
        this.sessionDatabaseService = sessionDatabaseService;
        this.fullTextIndex = fullTextIndex;
        this.llmService = llmService;
        this.intelligentProperties = intelligentProperties;
    }

    /** 搜索结果 */
    @Data
    public static class SearchResults {
        /** 会话摘列〃 */
        private List<SessionSummary> sessionSummaries;
        /** 匹配的标签列?*/
        private List<FullTextIndex.SearchResult> matchedTags;
        /** 总匹配数 */
        private int totalMatches;
        /** 搜索耗时（秒） */
        private long searchDurationMs;
    }

    /** 会话摘 */
    @Data
    public static class SessionSummary {
        private String conversationId;
        private String agentName;
        private String title;
        private String summary;
        private List<String> keywords;
        private int messageCount;
        private double relevanceScore;
        private List<String> tags;
        private LocalDateTime createdAt;
        private LocalDateTime generatedAt;
    }

    /** 鏈€近会?*/
    @Data
    public static class RecentSession {
        private String conversationId;
        private String agentName;
        private String title;
        private int messageCount;
        private LocalDateTime lastUpdatedAt;
        private LocalDateTime createdAt;
    }

    /** 搜索会话 */
    public SearchResults searchSessions(String query, String userId, String agentName, int limit) {
        long startTime = System.currentTimeMillis();
        try {
            String optimizedQuery = fullTextIndex.optimizeQuery(query);
            FullTextIndex.SearchOptions options = FullTextIndex.SearchOptions.defaults()
                    .limit(limit * 2).userId(userId);
            if (agentName != null && !agentName.isBlank()) {
                options.agentName(agentName);
            }

            List<FullTextIndex.SearchResult> searchResults = fullTextIndex.search(optimizedQuery, options);
            Map<String, List<FullTextIndex.SearchResult>> groupedResults = fullTextIndex
                    .searchByConversation(optimizedQuery, options);

            List<SessionSummary> sessionSummaries = new ArrayList<>();
            for (Map.Entry<String, List<FullTextIndex.SearchResult>> entry : groupedResults.entrySet()) {
                String conversationId = entry.getKey();
                List<FullTextIndex.SearchResult> results = entry.getValue();
                SessionSummaryEntity summaryEntity = sessionDatabaseService.getSessionSummary(conversationId);
                if (summaryEntity != null) {
                    SessionSummary summary = convertToSessionSummary(summaryEntity, results);
                    sessionSummaries.add(summary);
                }
            }

            sessionSummaries.sort(Comparator.comparingDouble(SessionSummary::getRelevanceScore).reversed());
            List<SessionSummary> finalResults = sessionSummaries.stream()
                    .limit(limit).collect(Collectors.toList());

            long duration = System.currentTimeMillis() - startTime;
            SearchResults results = new SearchResults();
            results.setSessionSummaries(finalResults);
            results.setMatchedTags(searchResults.stream()
                    .filter(r -> r.getTag().getUserId() != null && r.getTag().getUserId().equals(userId))
                    .collect(Collectors.toList()));
            results.setTotalMatches(searchResults.size());
            results.setSearchDurationMs(duration);

            log.debug("Session search for '{}' completed in {}ms, returned {} results",
                    query, duration, finalResults.size());
            return results;
        } catch (Exception e) {
            log.error("Failed to search sessions for query: {}", query, e);
            SearchResults errorResults = new SearchResults();
            errorResults.setSessionSummaries(Collections.emptyList());
            errorResults.setMatchedTags(Collections.emptyList());
            errorResults.setTotalMatches(0);
            errorResults.setSearchDurationMs(System.currentTimeMillis() - startTime);
            return errorResults;
        }
    }

    /** 搜索会话（简化版?*/
    public SearchResults searchSessions(String query, String userId) {
        return searchSessions(query, userId, null, 50);
    }

    /** 列出€近会?*/
    public List<RecentSession> listRecentSessions(String userId, String agentName, int limit) {
        try {
            List<SessionSummaryEntity> summaries;
            if (agentName != null && !agentName.isBlank()) {
                summaries = sessionDatabaseService.getUserSummaries(userId).stream()
                        .filter(s -> agentName.equals(s.getAgentName()))
                        .collect(Collectors.toList());
            } else {
                summaries = sessionDatabaseService.getUserSummaries(userId);
            }
            List<RecentSession> recentSessions = summaries.stream()
                    .sorted(Comparator.comparing(SessionSummaryEntity::getCreatedAt).reversed())
                    .limit(limit)
                    .map(this::convertToRecentSession)
                    .collect(Collectors.toList());
            log.debug("Listed {} recent sessions for user: {}", recentSessions.size(), userId);
            return recentSessions;
        } catch (Exception e) {
            log.error("Failed to list recent sessions for user: {}", userId, e);
            return Collections.emptyList();
        }
    }

    /** 列出€近会话（€化版?*/
    public List<RecentSession> listRecentSessions(String userId) {
        return listRecentSessions(userId, null, 20);
    }

    /** 生成会话摘 */
    public String summarizeSession(ConversationEntity conversation, String summaryType) {
        if (conversation == null || conversation.getMessages() == null ||
                conversation.getMessages().isEmpty()) {
            return "";
        }
        try {
            SessionSummaryEntity existingSummary = sessionDatabaseService.getSessionSummaryByType(
                    conversation.getId(), summaryType);
            if (existingSummary != null && existingSummary.getSummary() != null) {
                return existingSummary.getSummary();
            }
            String prompt = buildSummaryPrompt(conversation, summaryType);
            String summary = llmService.generateOrDefault(
                    "你是一个对话摘要生成专家，负责为对话生成简洁准确的摘要。",
                    prompt, "");
            List<String> keywords = sessionDatabaseService.extractKeywords(conversation);
            sessionDatabaseService.saveSessionSummary(
                    conversation.getId(), conversation.getAgentName(),
                    conversation.getUserId(), summary, summaryType, keywords,
                    conversation.getMessages());
            log.debug("Generated summary for conversation: {}, type: {}",
                    conversation.getId(), summaryType);
            return summary;
        } catch (Exception e) {
            log.error("Failed to summarize conversation: {}", conversation.getId(), e);
            return "";
        }
    }

    /** 生成会话摘（简化版?*/
    public String summarizeSession(ConversationEntity conversation) {
        return summarizeSession(conversation, "session_overview");
    }

    /** 为会话添加标?*/
    public void tagSession(ConversationEntity conversation, List<SessionTagEntity> tags) {
        if (conversation == null || tags == null || tags.isEmpty()) return;
        try {
            for (SessionTagEntity tag : tags) {
                tag.setConversationId(conversation.getId());
                tag.setAgentName(conversation.getAgentName());
                tag.setUserId(conversation.getUserId());
            }
            sessionDatabaseService.saveSessionTags(tags);
            log.debug("Tagged conversation: {} with {} tags", conversation.getId(), tags.size());
        } catch (Exception e) {
            log.error("Failed to tag conversation: {}", conversation.getId(), e);
        }
    }

    /** 构建摘要生成提示词 */
    private String buildSummaryPrompt(ConversationEntity conversation, String summaryType) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为以下对话生成一个").append(summaryType).append("摘要。\n\n");
        if ("session_overview".equals(summaryType)) {
            prompt.append("要求：\n1. 总结对话的主要内容和目标\n2. 概括讨论的关键点\n3. 提及任何重要结论或行动项\n4. 摘要长度控制在100-300字\n");
        } else if ("key_points".equals(summaryType)) {
            prompt.append("要求：\n1. 列出对话的关键点（使用项目符号）\n2. 每个关键点用一句话概括\n3. 突出重要的技术细节或决策\n");
        } else if ("outcomes".equals(summaryType)) {
            prompt.append("要求：\n1. 总结对话的成果和输出\n2. 列出已完成的任务或达成的共识\n3. 识别后续需要跟进的事项\n");
        }
        prompt.append("\n对话内：\n");
        StringBuilder contentBuilder = new StringBuilder();
        int charCount = 0;
        int maxChars = intelligentProperties.getLearningLoop().getMaxSessionChars();
        for (Msg msg : conversation.getMessages()) {
            String msgText = msg.toString();
            if (charCount + msgText.length() > maxChars) {
                contentBuilder.append("\n... (更消息已省?");
                break;
            }
            contentBuilder.append(msgText).append("\n");
            charCount += msgText.length();
        }
        prompt.append(contentBuilder.toString());
        return prompt.toString();
    }

    private SessionSummary convertToSessionSummary(SessionSummaryEntity entity,
            List<FullTextIndex.SearchResult> searchResults) {
        SessionSummary summary = new SessionSummary();
        summary.setConversationId(entity.getConversationId());
        summary.setAgentName(entity.getAgentName());
        summary.setSummary(entity.getSummary());
        summary.setMessageCount(entity.getMessageCount());
        summary.setCreatedAt(entity.getCreatedAt());
        summary.setGeneratedAt(entity.getGeneratedAt());
        summary.setKeywords(sessionDatabaseService.parseKeywords(entity.getKeywords()));
        if (searchResults != null && !searchResults.isEmpty()) {
            double avgScore = searchResults.stream()
                    .mapToDouble(FullTextIndex.SearchResult::getRelevanceScore)
                    .average().orElse(0.0);
            summary.setRelevanceScore(avgScore);
            List<String> tagNames = searchResults.stream()
                    .map(r -> r.getTag().getTagName()).distinct().collect(Collectors.toList());
            summary.setTags(tagNames);
        } else {
            summary.setRelevanceScore(0.0);
            summary.setTags(Collections.emptyList());
        }
        return summary;
    }

    private RecentSession convertToRecentSession(SessionSummaryEntity entity) {
        RecentSession recent = new RecentSession();
        recent.setConversationId(entity.getConversationId());
        recent.setAgentName(entity.getAgentName());
        recent.setMessageCount(entity.getMessageCount());
        recent.setCreatedAt(entity.getCreatedAt());
        recent.setLastUpdatedAt(entity.getUpdatedAt());
        recent.setTitle("");
        return recent;
    }

    /** 鍋ュ悍妫€鏌?*/
    public boolean healthCheck() {
        return sessionDatabaseService.healthCheck();
    }
}
