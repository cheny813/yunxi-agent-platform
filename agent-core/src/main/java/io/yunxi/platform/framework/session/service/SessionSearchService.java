package io.yunxi.platform.framework.session.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.framework.session.entity.SessionSummaryEntity;
import io.yunxi.platform.framework.session.entity.SessionTagEntity;
import io.yunxi.platform.framework.session.index.FullTextIndex;
import io.yunxi.platform.framework.intelligent.config.IntelligentProperties;
import io.yunxi.platform.framework.intelligent.llm.IntelligentLlmService;
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
 * 提供会话搜索、摘要生成和上下文恢复功能
 * 集成全文搜索和LLM摘要生成
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class SessionSearchService {

    /** 会话数据库服务 */
    private final SessionDatabaseService sessionDatabaseService;
    /** 全文索引服务 */
    private final FullTextIndex fullTextIndex;
    /** 智能LLM服务 */
    private final IntelligentLlmService llmService;
    /** 智能配置属性 */
    private final IntelligentProperties intelligentProperties;

    /**
     * 构造会话搜索服务
     *
     * @param sessionDatabaseService  会话数据库服务
     * @param fullTextIndex           全文索引服务
     * @param llmService              智能LLM服务
     * @param intelligentProperties   智能配置属性
     * @param objectMapper            JSON 对象映射器
     */
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

    /**
     * 搜索结果
     */
    @Data
    public static class SearchResults {
        /**
         * 会话摘要列表
         */
        private List<SessionSummary> sessionSummaries;

        /**
         * 匹配的标签列表
         */
        private List<FullTextIndex.SearchResult> matchedTags;

        /**
         * 总匹配数
         */
        private int totalMatches;

        /**
         * 搜索耗时（毫秒）
         */
        private long searchDurationMs;
    }

    /**
     * 会话摘要
     */
    @Data
    public static class SessionSummary {
        /**
         * 会话ID
         */
        private String conversationId;

        /**
         * Agent名称
         */
        private String agentName;

        /**
         * 会话标题
         */
        private String title;

        /**
         * 会话摘要
         */
        private String summary;

        /**
         * 关键词
         */
        private List<String> keywords;

        /**
         * 消息数量
         */
        private int messageCount;

        /**
         * 相关性评分（0-1）
         */
        private double relevanceScore;

        /**
         * 标签列表
         */
        private List<String> tags;

        /**
         * 创建时间
         */
        private LocalDateTime createdAt;

        /**
         * 摘要生成时间
         */
        private LocalDateTime generatedAt;
    }

    /**
     * 最近会话
     */
    @Data
    public static class RecentSession {
        /**
         * 会话ID
         */
        private String conversationId;

        /**
         * Agent名称
         */
        private String agentName;

        /**
         * 会话标题
         */
        private String title;

        /**
         * 消息数量
         */
        private int messageCount;

        /**
         * 最后更新时间
         */
        private LocalDateTime lastUpdatedAt;

        /**
         * 创建时间
         */
        private LocalDateTime createdAt;
    }

    /**
     * 搜索会话
     *
     * @param query     搜索查询
     * @param userId    用户ID
     * @param agentName Agent名称（可选）
     * @param limit     结果限制
     * @return 搜索结果
     */
    public SearchResults searchSessions(String query, String userId, String agentName, int limit) {
        long startTime = System.currentTimeMillis();

        try {
            // 优化查询
            String optimizedQuery = fullTextIndex.optimizeQuery(query);

            // 构建搜索选项
            FullTextIndex.SearchOptions options = FullTextIndex.SearchOptions.defaults()
                    .limit(limit * 2) // 获取更多结果用于后续处理
                    .userId(userId);

            if (agentName != null && !agentName.isBlank()) {
                options.agentName(agentName);
            }

            // 执行全文搜索
            List<FullTextIndex.SearchResult> searchResults = fullTextIndex.search(optimizedQuery, options);

            // 按会话分组
            Map<String, List<FullTextIndex.SearchResult>> groupedResults = fullTextIndex
                    .searchByConversation(optimizedQuery, options);

            // 构建会话摘要列表
            List<SessionSummary> sessionSummaries = new ArrayList<>();
            for (Map.Entry<String, List<FullTextIndex.SearchResult>> entry : groupedResults.entrySet()) {
                String conversationId = entry.getKey();
                List<FullTextIndex.SearchResult> results = entry.getValue();

                // 查询会话摘要
                SessionSummaryEntity summaryEntity = sessionDatabaseService.getSessionSummary(conversationId);
                if (summaryEntity != null) {
                    SessionSummary summary = convertToSessionSummary(summaryEntity, results);
                    sessionSummaries.add(summary);
                }
            }

            // 按相关性排序
            sessionSummaries.sort(Comparator.comparingDouble(SessionSummary::getRelevanceScore).reversed());

            // 限制结果数量
            List<SessionSummary> finalResults = sessionSummaries.stream()
                    .limit(limit)
                    .collect(Collectors.toList());

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

    /**
     * 搜索会话（简化版）
     *
     * @param query  搜索查询
     * @param userId 用户ID
     * @return 搜索结果
     */
    public SearchResults searchSessions(String query, String userId) {
        return searchSessions(query, userId, null, 50);
    }

    /**
     * 列出最近会话
     *
     * @param userId    用户ID
     * @param agentName Agent名称（可选）
     * @param limit     结果限制
     * @return 最近会话列表
     */
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

    /**
     * 列出最近会话（简化版）
     *
     * @param userId 用户ID
     * @return 最近会话列表
     */
    public List<RecentSession> listRecentSessions(String userId) {
        return listRecentSessions(userId, null, 20);
    }

    /**
     * 生成会话摘要
     *
     * @param conversation 会话实体
     * @param summaryType  摘要类型
     * @return 摘要内容
     */
    public String summarizeSession(ConversationEntity conversation, String summaryType) {
        if (conversation == null || conversation.getMessages() == null ||
                conversation.getMessages().isEmpty()) {
            return "";
        }

        try {
            // 检查是否已经存在摘要
            SessionSummaryEntity existingSummary = sessionDatabaseService.getSessionSummaryByType(
                    conversation.getId(), summaryType);

            if (existingSummary != null && existingSummary.getSummary() != null) {
                return existingSummary.getSummary();
            }

            // 生成新摘要
            String prompt = buildSummaryPrompt(conversation, summaryType);

            String summary = llmService.generateOrDefault(
                    "你是一个对话摘要生成专家，负责为对话生成简洁准确的摘要。",
                    prompt, "");

            // 提取关键词
            List<String> keywords = sessionDatabaseService.extractKeywords(conversation);

            // 保存摘要
            sessionDatabaseService.saveSessionSummary(
                    conversation.getId(),
                    conversation.getAgentName(),
                    conversation.getUserId(),
                    summary,
                    summaryType,
                    keywords,
                    conversation.getMessages());

            log.debug("Generated summary for conversation: {}, type: {}",
                    conversation.getId(), summaryType);

            return summary;
        } catch (Exception e) {
            log.error("Failed to summarize conversation: {}", conversation.getId(), e);
            return "";
        }
    }

    /**
     * 生成会话摘要（简化版）
     *
     * @param conversation 会话实体
     * @return 摘要内容
     */
    public String summarizeSession(ConversationEntity conversation) {
        return summarizeSession(conversation, "session_overview");
    }

    /**
     * 为会话添加标签
     *
     * @param conversation 会话实体
     * @param tags         标签列表
     */
    public void tagSession(ConversationEntity conversation, List<SessionTagEntity> tags) {
        if (conversation == null || tags == null || tags.isEmpty()) {
            return;
        }

        try {
            // 设置标签的会话信息
            for (SessionTagEntity tag : tags) {
                tag.setConversationId(conversation.getId());
                tag.setAgentName(conversation.getAgentName());
                tag.setUserId(conversation.getUserId());
            }

            // 批量保存标签
            sessionDatabaseService.saveSessionTags(tags);

            log.debug("Tagged conversation: {} with {} tags",
                    conversation.getId(), tags.size());
        } catch (Exception e) {
            log.error("Failed to tag conversation: {}", conversation.getId(), e);
        }
    }

    /**
     * 构建摘要生成提示词
     *
     * @param conversation 会话实体
     * @param summaryType  摘要类型
     * @return 提示词
     */
    private String buildSummaryPrompt(ConversationEntity conversation, String summaryType) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("请为以下对话生成一个").append(summaryType).append("摘要。\n\n");

        if ("session_overview".equals(summaryType)) {
            prompt.append("要求：\n");
            prompt.append("1. 总结对话的主要内容和目的\n");
            prompt.append("2. 概括讨论的关键点\n");
            prompt.append("3. 提及任何重要结论或行动项\n");
            prompt.append("4. 摘要长度控制在200-300字\n");
        } else if ("key_points".equals(summaryType)) {
            prompt.append("要求：\n");
            prompt.append("1. 列出对话中的关键点（使用项目符号）\n");
            prompt.append("2. 每个关键点用一句话概括\n");
            prompt.append("3. 突出重要的技术细节或决策\n");
        } else if ("outcomes".equals(summaryType)) {
            prompt.append("要求：\n");
            prompt.append("1. 总结对话的成果和产出\n");
            prompt.append("2. 列出已完成的任务或达成的共识\n");
            prompt.append("3. 识别后续需要跟进的事项\n");
        }

        prompt.append("\n对话内容：\n");

        // 添加消息内容（限制长度）
        StringBuilder contentBuilder = new StringBuilder();
        int charCount = 0;
        int maxChars = intelligentProperties.getLearningLoop().getMaxSessionChars();

        for (Msg msg : conversation.getMessages()) {
            String msgText = msg.toString();
            if (charCount + msgText.length() > maxChars) {
                // 添加截断提示
                contentBuilder.append("\n... (更多消息已省略)");
                break;
            }
            contentBuilder.append(msgText).append("\n");
            charCount += msgText.length();
        }

        prompt.append(contentBuilder.toString());

        return prompt.toString();
    }

    /**
     * 转换为会话摘要
     *
     * @param entity        摘要实体
     * @param searchResults 搜索结果
     * @return 会话摘要
     */
    private SessionSummary convertToSessionSummary(SessionSummaryEntity entity,
            List<FullTextIndex.SearchResult> searchResults) {
        SessionSummary summary = new SessionSummary();
        summary.setConversationId(entity.getConversationId());
        summary.setAgentName(entity.getAgentName());
        summary.setSummary(entity.getSummary());
        summary.setMessageCount(entity.getMessageCount());
        summary.setCreatedAt(entity.getCreatedAt());
        summary.setGeneratedAt(entity.getGeneratedAt());

        // 解析关键词
        summary.setKeywords(sessionDatabaseService.parseKeywords(entity.getKeywords()));

        // 计算平均相关性评分
        if (searchResults != null && !searchResults.isEmpty()) {
            double avgScore = searchResults.stream()
                    .mapToDouble(FullTextIndex.SearchResult::getRelevanceScore)
                    .average()
                    .orElse(0.0);
            summary.setRelevanceScore(avgScore);

            // 提取标签名称
            List<String> tagNames = searchResults.stream()
                    .map(r -> r.getTag().getTagName())
                    .distinct()
                    .collect(Collectors.toList());
            summary.setTags(tagNames);
        } else {
            summary.setRelevanceScore(0.0);
            summary.setTags(Collections.emptyList());
        }

        return summary;
    }

    /**
     * 转换为最近会话
     *
     * @param entity 摘要实体
     * @return 最近会话
     */
    private RecentSession convertToRecentSession(SessionSummaryEntity entity) {
        RecentSession recent = new RecentSession();
        recent.setConversationId(entity.getConversationId());
        recent.setAgentName(entity.getAgentName());
        recent.setMessageCount(entity.getMessageCount());
        recent.setCreatedAt(entity.getCreatedAt());
        recent.setLastUpdatedAt(entity.getUpdatedAt());
        // 注意：标题需要从原始会话中获取，这里暂时为空
        recent.setTitle("");
        return recent;
    }

    /**
     * 健康检查
     *
     * @return 是否健康
     */
    public boolean healthCheck() {
        return sessionDatabaseService.healthCheck();
    }
}
