package io.yunxi.platform.aistio.registry;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 活跃会话索引 —— aistio 契约 {@code GET /agentscope/sessions} 的数据源。
 *
 * <p>本类<b>始终加载</b>（不随 {@code yunxi.aistio.enabled} 开关），由 {@code ConversationDomainService}
 * 在会话创建 / 删除时写入，解决 {@code ConversationDomainService} 仅提供
 * {@code listConversationsByUserId}、<b>无全量列举</b>的问题。</p>
 *
 * <p>覆盖「平台启动后活跃过的会话」；冷会话（启动前已存在、从未被访问）不会在此索引中——
 * 这与 aistio 契约的「数据面实时上报会话」语义一致。</p>
 */
@Component
public class ActiveSessionRegistry {

    private final Map<String, SessionMeta> index = new ConcurrentHashMap<>();

    /**
     * 写入 / 刷新一个会话的索引。
     *
     * @param sessionId  会话 ID
     * @param userId     用户 ID（作为 workspace 命名空间）
     * @param agentName  智能体名称
     * @param createdAt  会话创建时间
     */
    public void register(String sessionId, String userId, String agentName, LocalDateTime createdAt) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        index.put(sessionId, new SessionMeta(sessionId, userId, agentName, createdAt));
    }

    /**
     * 移除一个会话索引（会话删除时调用）。
     *
     * @param sessionId 会话 ID
     */
    public void remove(String sessionId) {
        index.remove(sessionId);
    }

    /**
     * 读取单个会话元数据。
     *
     * @param sessionId 会话 ID
     * @return 元数据，不存在返回 {@code null}
     */
    public SessionMeta get(String sessionId) {
        return index.get(sessionId);
    }

    /**
     * 列出全部索引中的会话。
     *
     * @return 会话元数据列表
     */
    public List<SessionMeta> list() {
        return new ArrayList<>(index.values());
    }

    /**
     * 列出所有观测到的 workspace（userId 命名空间）集合。
     *
     * @return workspace ID 集合
     */
    public Set<String> workspaceIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (SessionMeta meta : index.values()) {
            if (meta.userId() != null && !meta.userId().isBlank()) {
                ids.add(meta.userId());
            }
        }
        return ids;
    }

    /**
     * 会话元数据（按<b>会话维度</b>）。
     * 注意：区别于 AgentScope-Java 的 {@code PhaseTracker}（其以 agentName 为键、只保留最近一次执行），
     * 本元数据以会话为单位，可正确支撑 {@code /sessions/{id}/state}。
     *
     * @param sessionId  会话 ID
     * @param userId     用户 ID
     * @param agentName  智能体名称
     * @param createdAt  创建时间
     */
    public record SessionMeta(String sessionId, String userId, String agentName, LocalDateTime createdAt) {
    }

    /** 仅供单元测试：清空索引。 */
    public void clearForTest() {
        index.clear();
    }
}
