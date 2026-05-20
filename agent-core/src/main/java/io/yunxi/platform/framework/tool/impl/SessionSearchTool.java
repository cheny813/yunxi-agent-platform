package io.yunxi.platform.framework.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.framework.session.service.SessionSearchService;
import io.yunxi.platform.framework.session.service.SessionDatabaseService;
import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolExecutionException;
import io.yunxi.platform.framework.tool.ToolInput;
import io.yunxi.platform.framework.tool.ToolResult;
import io.yunxi.platform.shared.security.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话搜索工具
 * <p>
 * 用于Agent搜索历史会话、查看最近会话和获取会话摘要
 * </p>
 * <p>
 * 注意：需要配置会话搜索系统才可用
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.session.search.enabled", havingValue = "true", matchIfMissing = false)
public class SessionSearchTool implements Tool {

    /** 会话搜索服务 */
    private final SessionSearchService sessionSearchService;
    /** 会话数据库服务 */
    private final SessionDatabaseService sessionDatabaseService;
    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper;
    /** 安全上下文 */
    private final SecurityContext securityContext;

    /**
     * 构造会话搜索工具
     *
     * @param sessionSearchService   会话搜索服务
     * @param sessionDatabaseService 会话数据库服务
     * @param objectMapper           JSON 对象映射器
     * @param securityContext        安全上下文
     */
    public SessionSearchTool(SessionSearchService sessionSearchService,
            SessionDatabaseService sessionDatabaseService,
            ObjectMapper objectMapper,
            SecurityContext securityContext) {
        this.sessionSearchService = sessionSearchService;
        this.sessionDatabaseService = sessionDatabaseService;
        this.objectMapper = objectMapper;
        this.securityContext = securityContext;
    }

    @Override
    public String getName() {
        return "session_search";
    }

    @Override
    public String getDescription() {
        return "搜索历史会话、查看最近会话和获取会话摘要。可以用来查找用户之前的对话记录和相关讨论。";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "action": {
                            "type": "string",
                            "enum": ["search", "list_recent", "get_summary"],
                            "description": "操作类型：search（搜索会话）、list_recent（列出最近会话）、get_summary（获取会话摘要）"
                        },
                        "query": {
                            "type": "string",
                            "description": "搜索查询文本（仅用于search操作）"
                        },
                        "userId": {
                            "type": "string",
                            "description": "用户ID（可选，默认使用当前用户）"
                        },
                        "agentName": {
                            "type": "string",
                            "description": "Agent名称（可选，用于过滤特定Agent的会话）"
                        },
                        "limit": {
                            "type": "integer",
                            "default": 20,
                            "description": "结果数量限制"
                        },
                        "conversationId": {
                            "type": "string",
                            "description": "会话ID（仅用于get_summary操作）"
                        }
                    },
                    "required": ["action"]
                }
                """;
    }

    @Override
    public ToolResult execute(ToolInput input) throws ToolExecutionException {
        long startTime = System.currentTimeMillis();

        try {
            String action = input.getString("action");
            String userId = input.getString("userId");

            // 获取安全上下文的用户ID（如果未提供）
            if (userId == null || userId.isBlank()) {
                // 从安全上下文获取用户ID
                userId = securityContext.getCurrentUserId();
                // 如果安全上下文也没有，使用默认值
                if (userId == null || userId.isBlank()) {
                    userId = "default_user";
                }
            }

            String agentName = input.getString("agentName");
            int limit = input.getInt("limit", 20);

            log.info("执行会话搜索操作: action={}, userId={}, agentName={}, limit={}",
                    action, userId, agentName, limit);

            Map<String, Object> output = new HashMap<>();

            switch (action) {
                case "search":
                    output.putAll(executeSearch(input, userId, agentName, limit));
                    break;

                case "list_recent":
                    output.putAll(executeListRecent(userId, agentName, limit));
                    break;

                case "get_summary":
                    output.putAll(executeGetSummary(input, userId));
                    break;

                default:
                    return ToolResult.error("未知的操作类型: " + action);
            }

            ToolResult toolResult = ToolResult.success(output);
            toolResult.setDurationMs(System.currentTimeMillis() - startTime);
            return toolResult;

        } catch (Exception e) {
            log.error("会话搜索工具执行失败", e);
            throw new ToolExecutionException(getName(), "执行会话搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行搜索操作
     */
    private Map<String, Object> executeSearch(ToolInput input, String userId, String agentName, int limit) {
        String query = input.getString("query");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("search操作需要提供query参数");
        }

        SessionSearchService.SearchResults results = sessionSearchService.searchSessions(
                query, userId, agentName, limit);

        Map<String, Object> output = new HashMap<>();
        output.put("action", "search");
        output.put("query", query);
        output.put("totalMatches", results.getTotalMatches());
        output.put("searchDurationMs", results.getSearchDurationMs());
        output.put("sessionCount", results.getSessionSummaries().size());

        // 转换会话摘要为Map
        List<Map<String, Object>> sessions = results.getSessionSummaries().stream()
                .map(this::convertSessionSummaryToMap)
                .toList();

        output.put("sessions", sessions);

        log.info("会话搜索完成: query={}, results={}, duration={}ms",
                query, sessions.size(), results.getSearchDurationMs());

        return output;
    }

    /**
     * 执行列出最近会话操作
     */
    private Map<String, Object> executeListRecent(String userId, String agentName, int limit) {
        List<SessionSearchService.RecentSession> recentSessions = sessionSearchService.listRecentSessions(userId,
                agentName, limit);

        Map<String, Object> output = new HashMap<>();
        output.put("action", "list_recent");
        output.put("userId", userId);
        output.put("count", recentSessions.size());

        // 转换为Map
        List<Map<String, Object>> sessions = recentSessions.stream()
                .map(this::convertRecentSessionToMap)
                .toList();

        output.put("sessions", sessions);

        log.info("列出最近会话完成: userId={}, count={}", userId, sessions.size());

        return output;
    }

    /**
     * 执行获取会话摘要操作
     */
    private Map<String, Object> executeGetSummary(ToolInput input, String userId) {
        String conversationId = input.getString("conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("get_summary操作需要提供conversationId参数");
        }

        // 从数据库获取会话摘要
        var summaryEntity = sessionDatabaseService.getSessionSummary(conversationId);

        Map<String, Object> output = new HashMap<>();
        output.put("action", "get_summary");
        output.put("conversationId", conversationId);

        if (summaryEntity != null) {
            output.put("title", summaryEntity.getTitle());
            output.put("summary", summaryEntity.getSummary());
            output.put("agentName", summaryEntity.getAgentName());
            output.put("messageCount", summaryEntity.getMessageCount());
            output.put("keywords", summaryEntity.getKeywords());
            output.put("tags", summaryEntity.getTags());
            output.put("createdAt", summaryEntity.getCreatedAt());
            output.put("generatedAt", summaryEntity.getGeneratedAt());
            output.put("userId", summaryEntity.getUserId());

            log.info("获取会话摘要成功: conversationId={}, title={}", conversationId, summaryEntity.getTitle());
        } else {
            output.put("summary", "未找到该会话的摘要信息");
            output.put("error", "会话摘要不存在");
            log.warn("会话摘要不存在: conversationId={}", conversationId);
        }

        return output;
    }

    /**
     * 转换会话摘要为Map
     */
    private Map<String, Object> convertSessionSummaryToMap(SessionSearchService.SessionSummary summary) {
        Map<String, Object> map = new HashMap<>();
        map.put("conversationId", summary.getConversationId());
        map.put("agentName", summary.getAgentName());
        map.put("title", summary.getTitle());
        map.put("summary", summary.getSummary());
        map.put("keywords", summary.getKeywords());
        map.put("messageCount", summary.getMessageCount());
        map.put("relevanceScore", summary.getRelevanceScore());
        map.put("tags", summary.getTags());
        map.put("createdAt", summary.getCreatedAt());
        map.put("generatedAt", summary.getGeneratedAt());
        return map;
    }

    /**
     * 转换最近会话为Map
     */
    private Map<String, Object> convertRecentSessionToMap(SessionSearchService.RecentSession session) {
        Map<String, Object> map = new HashMap<>();
        map.put("conversationId", session.getConversationId());
        map.put("agentName", session.getAgentName());
        map.put("title", session.getTitle());
        map.put("messageCount", session.getMessageCount());
        map.put("lastUpdatedAt", session.getLastUpdatedAt());
        map.put("createdAt", session.getCreatedAt());
        return map;
    }

    @Override
    public boolean isEnabled() {
        return sessionSearchService != null && sessionSearchService.healthCheck();
    }
}
