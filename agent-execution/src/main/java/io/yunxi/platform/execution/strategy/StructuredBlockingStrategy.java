package io.yunxi.platform.execution.strategy;

import java.time.Duration;
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
import io.yunxi.platform.agent.runtime.AgentInvoker;

import io.yunxi.platform.shared.codec.CodecSpec;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.context.SchemaTarget;
import io.yunxi.platform.execution.spi.ExecutionStrategy;
import io.yunxi.platform.integration.structured.SchemaClassRegistry;
import io.yunxi.platform.trace.LlmMetrics;

/**
 * 结构化阻塞执行策略（非流式结构化输出通道）。
 *
 * <p>结构化（非流式）输出通道：与普通阻塞通道一样经过拦截器链
 * （AuthResolve 解析 Agent/RuntimeContext、Memory 组装单条输入、
 * 权限（由底层框架中间件执行）、Audit 审计），再由本策略完成结构化调用：</p>
 * <ul>
 *   <li>Schema 解析优先级：请求内联 Schema（Map → JsonNode）> 命名 Schema
 *       （agentName+schemaName 查 {@link SchemaClassRegistry}）> 默认 Schema 表；</li>
 *   <li>结构化目标（SchemaTarget）写入 ctx 属性 {@value SchemaTarget#ATTR_SCHEMA_TARGET}，
 *       供门面（ChatAppService#chatStructured）从 Msg 提取结构化数据；</li>
 *   <li>调用方式：JsonNode 与 Class 均走带 {@code RuntimeContext} 的
 *       {@code harnessAgent.call(List.of(msg), schema, rc)} 重载，
 *       保证内联与命名 Schema 两条路径都携带身份上下文；</li>
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

    private final SchemaClassRegistry schemaClassRegistry;
    private final LlmMetrics llmMetrics;
    private final ObjectMapper objectMapper = CodecSpec.storage();

    /** 审计开关（配置项，默认关闭） */
    @Value("${agentscope.core.audit.enabled:false}")
    private boolean auditEnabled;

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
        ctx.setAttribute(SchemaTarget.ATTR_SCHEMA_TARGET, target);

        // 结构化输出使用单条输入消息
        Msg inputMessage = ctx.getInputMessage();
        Duration timeout = Duration.ofSeconds(conversationTimeoutSeconds);

        // 内联 Schema 与命名/默认 Schema 两条结构化路径统一构造带 userId/sessionId 的
        // 运行时上下文，确保身份上下文（多租户隔离、轨迹归属、画像特征）在两条路径
        // 表现一致 —— 此前内联 Schema 分支缺少 RuntimeContext，造成身份注入不一致。
        RuntimeContext rc = StrategyContextSupport.build(ctx,
                ctx.getRequest().getConversationId(), auditEnabled);

        // 会话级工具组激活已下沉为协议无关的 SessionToolGroupActivationMiddleware（onAgent 钩子），
        // 经 AgentConfigurer.configureMiddlewares 挂到共享 Agent，对所有协议统一生效。

        // 结构化调用只有「返回结构化结果」的重载，没有返回事件流的重载，
        // 因此本通道无法归集轨迹 —— 归集挂在事件流上，而事件流在此不可得。
        // 这是框架 API 表面所限，已在架构文档登记，不在本类内绕过。
        Msg response;
        if (target.schemaNode() != null) {
            // 内联 JSON Schema：带 RuntimeContext 的结构化调用（保持多租户隔离）
        response = AgentInvoker.call((HarnessAgent) agent, inputMessage, target.schemaNode(), rc)
                .block(timeout);
        } else {
            // 命名/默认 Schema 类：带 RuntimeContext（保持多租户隔离）
        response = AgentInvoker.call((HarnessAgent) agent, inputMessage, target.schemaClass(), rc)
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


}
