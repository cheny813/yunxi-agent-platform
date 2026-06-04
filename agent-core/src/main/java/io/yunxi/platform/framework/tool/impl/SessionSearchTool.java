package io.yunxi.platform.framework.tool.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.platform.framework.session.service.SessionDatabaseService;
import io.yunxi.platform.framework.session.service.SessionSearchService;
import io.yunxi.platform.shared.security.SecurityContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 会话搜索工具
 * <p>
 * 用于Agent搜索历史会话、查看最近会话和获取会话摘要
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.session.search.enabled", havingValue = "true", matchIfMissing = false)
public class SessionSearchTool {

    private final SessionSearchService sessionSearchService;
    private final SessionDatabaseService sessionDatabaseService;
    private final ObjectMapper objectMapper;
    private final SecurityContext securityContext;

    public SessionSearchTool(SessionSearchService sessionSearchService,
            SessionDatabaseService sessionDatabaseService,
            ObjectMapper objectMapper,
            SecurityContext securityContext) {
        this.sessionSearchService = sessionSearchService;
        this.sessionDatabaseService = sessionDatabaseService;
        this.objectMapper = objectMapper;
        this.securityContext = securityContext;
    }

    @Tool(name = "session_search", description = "搜索历史会话、查看最近会话和获取会话摘要。可以用来查找用户之前的对话记录和相关讨论。")
    public String search(
            @ToolParam(name = "action", description = "操作类型：search（搜索会话）、list_recent（列出最近会话）、get_summary（获取会话摘要）") String action,
            @ToolParam(name = "query", description = "搜索查询文本（仅用于search操作）") String query,
            @ToolParam(name = "userId", description = "用户ID（可选，默认使用当前用户）") String userId,
            @ToolParam(name = "agentName", description = "Agent名称（可选，用于过滤特定Agent的会话）") String agentName,
            @ToolParam(name = "limit", description = "结果数量限制，默认20") Integer limit,
            @ToolParam(name = "conversationId", description = "会话ID（仅用于get_summary操作）") String conversationId) {
        long startTime = System.currentTimeMillis();
        try {
            if (action == null)
                action = "search";
            if (limit == null || limit <= 0)
                limit = 20;

            if (userId == null || userId.isBlank()) {
                userId = securityContext.getCurrentUserId();
                if (userId == null || userId.isBlank())
                    userId = "default_user";
            }

            log.info("执行会话搜索操作: action={}, userId={}", action, userId);

            return switch (action) {
                case "search" -> executeSearch(query, userId, agentName, limit);
                case "list_recent" -> executeListRecent(userId, agentName, limit);
                case "get_summary" -> executeGetSummary(conversationId, userId);
                default -> "未知的操作类型: " + action;
            };
        } catch (Exception e) {
            log.error("会话搜索工具执行失败", e);
            return "执行会话搜索失败: " + e.getMessage();
        }
    }

    private String executeSearch(String query, String userId, String agentName, int limit) {
        if (query == null || query.isBlank())
            return "search操作需要提供query参数";
        var results = sessionSearchService.searchSessions(query, userId, agentName, limit);
        return String.format("{\"action\":\"search\",\"query\":\"%s\",\"totalMatches\":%d,\"sessionCount\":%d}",
                query, results.getTotalMatches(), results.getSessionSummaries().size());
    }

    private String executeListRecent(String userId, String agentName, int limit) {
        var recentSessions = sessionSearchService.listRecentSessions(userId, agentName, limit);
        return String.format("{\"action\":\"list_recent\",\"userId\":\"%s\",\"count\":%d}", userId,
                recentSessions.size());
    }

    private String executeGetSummary(String conversationId, String userId) {
        if (conversationId == null || conversationId.isBlank())
            return "get_summary操作需要提供conversationId参数";
        var summaryEntity = sessionDatabaseService.getSessionSummary(conversationId);
        if (summaryEntity != null) {
            return String.format(
                    "{\"action\":\"get_summary\",\"conversationId\":\"%s\",\"title\":\"%s\",\"summary\":\"%s\"}",
                    conversationId, summaryEntity.getTitle(), summaryEntity.getSummary());
        }
        return "未找到该会话的摘要信息";
    }
}
