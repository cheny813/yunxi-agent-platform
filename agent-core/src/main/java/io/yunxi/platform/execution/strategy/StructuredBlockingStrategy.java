package io.yunxi.platform.execution.strategy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.agent.AgentConfigurer;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.spi.ExecutionStrategy;
import io.yunxi.platform.structured.SchemaClassRegistry;
import io.yunxi.platform.tracing.LlmMetrics;

/**
 * 结构化阻塞执行策略（非流式结构化输出通道）。
 *
 * <p>结构化（非流式）输出通道：与普通阻塞通道一样经过拦截器链
 * （AuthResolve 解析 Agent/RuntimeContext、Memory 组装单条输入、
 * PermissionContext 权限快照、Audit 审计），再由本策略完成结构化调用：</p>
 * <ul>
 *   <li>Schema 解析优先级：请求内联 Schema（Map → JsonNode）> 命名 Schema
 *       （agentName+schemaName 查 {@link SchemaClassRegistry}）> 默认 Schema 表；</li>
 *   <li>结构化目标（SchemaTarget）写入 ctx 属性 {@value #ATTR_SCHEMA_TARGET}，
 *       供门面（ChatAppService#chatStructured）从 Msg 提取结构化数据；</li>
 *   <li>调用方式：JsonNode 走 {@code agent.call(msg, schemaNode)}，
 *       Class 走 {@code harnessAgent.call(List.of(msg), schemaClass, rc)}；</li>
 *   <li>超时读取 {@code conversation.timeout-seconds}（默认 300s）。</li>
 * </ul>
 *
 * <p>注：结构化输出使用单条输入消息；IntentPipelineInterceptor
 * 对结构化输出跳过意图改道（Schema 按 agentName 注册，改道会导致错配）。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class StructuredBlockingStrategy implements ExecutionStrategy {

    /** 结构化目标（SchemaTarget）在 ExecutionContext.attributes 中的键（供门面提取结构化数据） */
    public static final String ATTR_SCHEMA_TARGET = "structuredTarget";

    /** 结构化输出 Schema 目标（二选一：命名/默认 Schema 类 或 请求内联 JSON Schema 节点） */
    public record SchemaTarget(Class<?> schemaClass, JsonNode schemaNode) {
    }

    private final SchemaClassRegistry schemaClassRegistry;
    private final LlmMetrics llmMetrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 对话接口等待超时时间（秒） */
    @Value("${conversation.timeout-seconds:300}")
    private int conversationTimeoutSeconds;

    public StructuredBlockingStrategy(SchemaClassRegistry schemaClassRegistry,
                                      LlmMetrics llmMetrics) {
        this.schemaClassRegistry = schemaClassRegistry;
        this.llmMetrics = llmMetrics;
    }

    @Override
    public boolean supports(ExecutionContext ctx) {
        return !ctx.getRequest().isStreaming() && ctx.getRequest().isStructuredOutput();
    }

    @Override
    public Object execute(ExecutionContext ctx) {
        Agent agent = ctx.getResolvedAgent();
        if (agent == null) {
            throw new IllegalStateException("Agent 未解析");
        }

        SchemaTarget target = resolveSchemaTarget(ctx.getAgentName(), ctx.getRequest());
        if (target == null) {
            throw new IllegalArgumentException(
                    "未配置 Schema，请在 Agent 配置中设置 schema_class 或在请求中提供 schema 参数");
        }
        ctx.setAttribute(ATTR_SCHEMA_TARGET, target);

        // 结构化输出使用单条输入消息
        Msg inputMessage = ctx.getInputMessage();
        Duration timeout = Duration.ofSeconds(conversationTimeoutSeconds);

        Msg response;
        if (target.schemaNode() != null) {
            // 内联 JSON Schema：调用无 RuntimeContext 重载的 agent.call
            response = agent.call(inputMessage, target.schemaNode()).block(timeout);
        } else {
            // 命名/默认 Schema 类：带 RuntimeContext（保持多租户隔离）
            RuntimeContext rc = buildRuntimeContext(ctx.getRequest().getUserId(),
                    ctx.getRequest().getConversationId());
            // 会话级工具组激活：覆盖持久化/遗留空激活组，确保 MCP 工具在每次会话可用
            AgentConfigurer.activateSessionToolGroups((HarnessAgent) agent, rc.getUserId(), rc.getSessionId());
            response = ((HarnessAgent) agent)
                    .call(List.of(inputMessage), target.schemaClass(), rc)
                    .block(timeout);
        }

        // usage 记录：以原始 agentName 作维度
        if (response != null && response.getUsage() != null) {
            llmMetrics.recordAndLogUsage(ctx.getAgentName(), "yunxi", response.getUsage());
        }
        return response;
    }

    /**
     * 解析结构化输出 Schema 目标。
     *
     * <p>优先级：内联 Schema > 命名 Schema/默认 Schema 表。</p>
     */
    private SchemaTarget resolveSchemaTarget(String agentName,
                                             io.yunxi.platform.execution.ExecutionRequest request) {
        Map<String, Object> inlineSchema = request.getStructuredSchema();
        if (inlineSchema != null && !inlineSchema.isEmpty()) {
            return new SchemaTarget(null, objectMapper.valueToTree(inlineSchema));
        }
        Class<?> schemaClass = schemaClassRegistry.getSchema(agentName, request.getStructuredSchemaName());
        return schemaClass != null ? new SchemaTarget(schemaClass, null) : null;
    }

    private static RuntimeContext buildRuntimeContext(String userId, String sessionId) {
        RuntimeContext.Builder b = RuntimeContext.builder();
        if (userId != null && !userId.isBlank()) {
            b.userId(userId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            b.sessionId(sessionId);
        }
        return b.build();
    }
}
