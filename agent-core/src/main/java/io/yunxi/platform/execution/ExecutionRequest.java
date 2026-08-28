package io.yunxi.platform.execution;

import java.util.List;
import java.util.Map;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.dto.ConfirmResultRequest;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

/**
 * 执行请求归一化对象。
 *
 * <p>ChatAppService 的 4 个 public 入口（chat / chatWithConversation / chatStream /
 * chatStreamWithConversation）请求类型不同（ChatRequest / ConversationChatRequest /
 * StreamChatRequest），在调用引擎前由门面归一化为本对象，使拦截器/策略与具体请求类型解耦。</p>
 *
 * @author yunxi-agent-platform
 */
@Getter
@Builder(builderClassName = "Builder")
public class ExecutionRequest {

    /** 用户原始消息 */
    private final String message;
    /** 页面上下文数据（可为 null/空） */
    private final Map<String, Object> contextData;
    /** 记忆配置（可为 null，表示 NONE） */
    private final MemoryConfig memoryConfig;
    /**
     * 是否包含历史消息；null 表示未显式设置，视为 true（会话型入口语义）。
     * 采用包装类型 + getter 兜底默认值，避免依赖 lombok 生成的 Builder.Default 类型。
     */
    @Getter(AccessLevel.NONE)
    private final Boolean includeHistory;
    /** profile（可为 null） */
    private final String profile;
    /** 是否 A2A 协作模式 */
    private final boolean useA2A;
    /** 是否深度模式 */
    private final boolean deepMode;
    /** 是否启用思考事件 */
    private final boolean enableThinking;
    /** 是否 QUICK 阻塞通道 */
    private final boolean quickMode;
    /** 是否结构化输出 */
    private final boolean structuredOutput;
    /** 历史消息（会话型入口提供；非会话型为空） */
    private final List<Msg> historyMessages;
    /** 目标 Agent 名（来自 URL 路径；改道后由拦截器重写 ctx.resolvedAgent） */
    private final String agentName;
    /** 会话 ID（可为 null，表示非会话型入口） */
    private final String conversationId;
    /** 用户 ID（门面完成解析：会话优先取会话属主，否则 SecurityContext） */
    private final String userId;
    /** 是否流式通道（true=StreamingStrategy，false=BlockingStrategy） */
    private final boolean streaming;
    /** 结构化输出内联 JSON Schema（可为 null；解析优先级高于 structuredSchemaName 与默认 Schema 表） */
    private final Map<String, Object> structuredSchema;
    /** 结构化输出命名 Schema（可为 null；按 agentName+schemaName 查 SchemaClassRegistry） */
    private final String structuredSchemaName;
    /**
     * 人机确认（HITL）结果回传列表（可为 null/空）。
     *
     * <p>非空表示本次请求用于恢复因权限确认而挂起的对话：拦截器会将其转换为
     * AgentScope 的 {@code ConfirmResult} 并写入输入消息元数据
     * （{@code Msg.METADATA_CONFIRM_RESULTS}），由框架继续或终止对应工具调用。</p>
     */
    private final List<ConfirmResultRequest> confirmResults;

    /**
     * 是否包含历史消息：未显式设置（null）时视为 true（与历史会话入口语义一致）。
     *
     * @return true=注入历史消息
     */
    public boolean isIncludeHistory() {
        return includeHistory == null || includeHistory;
    }
}
