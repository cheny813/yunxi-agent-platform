package io.yunxi.platform.execution.interceptors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.spi.ExecutionInterceptor;
import io.yunxi.platform.shared.dto.ConfirmResultRequest;

/**
 * 人机确认（HITL）结果回传拦截器。
 *
 * <p>当 Agent 执行需人工确认的工具（由 Agent 配置的 {@code extensions.hitl.toolGate}
 * 指定）时，AgentScope 权限引擎会挂起 Agent 并推送 {@code REQUIRE_USER_CONFIRM} 事件。
 * 此时必须由调用方携带确认结果重新发起请求，Agent 才会继续。</p>
 *
 * <p>本拦截器只做<b>参数转换</b>：把请求中的 {@link ConfirmResultRequest} 列表转换为
 * AgentScope 的 {@link ConfirmResult}，写入输入消息的
 * {@code Msg.METADATA_CONFIRM_RESULTS} 元数据键；后续的校验（是否对应处于 ASKING
 * 状态的工具调用）、执行或拒绝全部由 AgentScope 完成（见
 * {@code ReActAgent} 的 {@code extractAndValidateConfirmResults}）。yunxi 不实现确认语义。</p>
 *
 * <p><b>顺序</b>：位于 Memory（150）之后、IntentPipeline（200）之前。
 * 依赖 Memory 已构造好输入消息；IntentPipeline 在改写消息时会保留
 * {@code metadata}，故本拦截器注入的确认结果不会被覆盖。</p>
 *
 * <p>请求未携带确认结果时本拦截器不执行任何操作，行为与原先完全一致。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class HITLConfirmInterceptor implements ExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(HITLConfirmInterceptor.class);

    @Override
    public int getOrder() {
        return 175;
    }

    @Override
    public void preHandle(ExecutionContext ctx) {
        List<ConfirmResultRequest> requests = ctx.getRequest().getConfirmResults();
        if (requests == null || requests.isEmpty()) {
            return;
        }

        Msg userMsg = ctx.getInputMessage();
        if (userMsg == null) {
            log.warn("收到确认结果但输入消息为空（Memory 未构造？），忽略: agentName={}",
                    ctx.getAgentName());
            return;
        }

        List<ConfirmResult> confirmResults = new ArrayList<>();
        for (ConfirmResultRequest req : requests) {
            if (req == null || req.getToolCallId() == null || req.getToolCallId().isBlank()) {
                // 缺少工具调用 ID 的项无法与待确认调用对应，跳过以免框架校验失败中断整轮恢复
                log.warn("确认结果缺少 toolCallId，已跳过: {}", req);
                continue;
            }
            ToolUseBlock toolCall =
                    new ToolUseBlock(req.getToolCallId(), req.getToolName(), req.getInput());
            confirmResults.add(new ConfirmResult(req.isApproved(), toolCall));
        }
        if (confirmResults.isEmpty()) {
            return;
        }

        // 保留原有元数据（含上下文注入等），仅追加确认结果键
        Map<String, Object> metadata = new HashMap<>();
        if (userMsg.getMetadata() != null) {
            metadata.putAll(userMsg.getMetadata());
        }
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);

        Msg enriched = Msg.builder()
                .id(userMsg.getId())
                .name(userMsg.getName())
                .role(userMsg.getRole())
                .content(userMsg.getContent())
                .metadata(metadata)
                .build();

        ctx.setInputMessage(enriched);
        // 同步替换输入消息列表中的用户消息（策略层以该列表作为 agent.call 入参）
        List<Msg> messages = ctx.getInputMessages();
        if (messages != null && !messages.isEmpty()) {
            messages.set(messages.size() - 1, enriched);
        }

        int approved = 0;
        for (ConfirmResult r : confirmResults) {
            if (r.isConfirmed()) {
                approved++;
            }
        }
        log.info("已注入人机确认结果: agentName={}, conversationId={}, 批准={}, 拒绝={}",
                ctx.getAgentName(), ctx.getConversationId(),
                approved, confirmResults.size() - approved);
    }
}
