package io.yunxi.platform.tool.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.yunxi.platform.session.service.SessionDatabaseService;
import io.yunxi.platform.session.service.SessionSearchService;
import io.yunxi.platform.security.auth.SecurityContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 会话搜索工具
 * <p>
 * 用于 Agent 搜索历史会话、查看最近会话和获取会话摘要
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

    /**
     * 创建会话搜索工具
     *
     * @param sessionSearchService   会话搜索服务（全文检索）
     * @param sessionDatabaseService 会话数据库服务（摘要查询）
     * @param objectMapper           JSON 序列化器
     * @param securityContext        安全上下文，用于获取当前用户
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

    @Tool(name = "session_search", description = "Search history conversations, view recent conversations and get conversation summaries. Can be used to find previous chat records and related discussions.")
    public String search(
            @ToolParam(name = "action", description = "Action type: search, list_recent, get_summary") String action,
            @ToolParam(name = "query", description = "Search query text (only for search action)") String query,
            @ToolParam(name = "userId", description = "User ID (optional, defaults to current user)") String userId,
            @ToolParam(name = "agentName", description = "Agent name (optional, for filtering by specific Agent)") String agentName,
            @ToolParam(name = "limit", description = "Result count limit, default 10") Integer limit,
            @ToolParam(name = "conversationId", description = "Conversation ID (only for get_summary action)") String conversationId) {
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
                default -> "未知的操作类型：" + action;
            };
        } catch (Exception e) {
            log.error("会话搜索工具执行失败", e);
            return "执行会话搜索失败: " + e.getMessage();
        }
    }

    /**
     * 执行会话全文搜索。
     *
     * @param query 搜索关键词
     * @param userId 用户标识
     * @param agentName 可选的 Agent 名称过滤
     * @param limit 返回数量限制
     * @return 搜索结果 JSON（含总命中数与会话数）
     */
    private String executeSearch(String query, String userId, String agentName, int limit) {
        if (query == null || query.isBlank())
            return "search 操作需要提供 query 参数";
        var results = sessionSearchService.searchSessions(query, userId, agentName, limit);
        return String.format("{\"action\":\"search\",\"query\":\"%s\",\"totalMatches\":%d,\"sessionCount\":%d}",
                query, results.getTotalMatches(), results.getSessionSummaries().size());
    }

    /**
     * 列出用户最近的会话。
     *
     * @param userId 用户标识
     * @param agentName 可选的 Agent 名称过滤
     * @param limit 返回数量限制
     * @return 最近会话列表 JSON
     */
    private String executeListRecent(String userId, String agentName, int limit) {
        var recentSessions = sessionSearchService.listRecentSessions(userId, agentName, limit);
        return String.format("{\"action\":\"list_recent\",\"userId\":\"%s\",\"count\":%d}", userId,
                recentSessions.size());
    }

    /**
     * 获取指定会话的摘要（标题 + 摘要文本）。
     *
     * @param conversationId 会话标识
     * @param userId 用户标识（当前保留用于日志/扩展）
     * @return 会话摘要 JSON，未找到时返回提示
     */
    private String executeGetSummary(String conversationId, String userId) {
        if (conversationId == null || conversationId.isBlank())
            return "get_summary 操作需要提供 conversationId 参数";
        var summaryEntity = sessionDatabaseService.getSessionSummary(conversationId);
        if (summaryEntity != null) {
            return String.format(
                    "{\"action\":\"get_summary\",\"conversationId\":\"%s\",\"title\":\"%s\",\"summary\":\"%s\"}",
                    conversationId, summaryEntity.getTitle(), summaryEntity.getSummary());
        }
        return "Summary info not found for this conversation";
    }
}
