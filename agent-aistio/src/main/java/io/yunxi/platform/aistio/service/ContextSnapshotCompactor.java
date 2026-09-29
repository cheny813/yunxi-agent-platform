package io.yunxi.platform.aistio.service;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.contract.dto.ContextSnapshot;
import io.yunxi.platform.agent.model.ModelFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 将 aistio {@code /context} 端点返回的历史窗口升级为与主体 Agent 一致的框架运行时压缩。
 *
 * <p>复用 AgentScope-Java 的 {@link ConversationCompactor}（CompactionMiddleware 的同款压缩内核），
 * 而非在 yunxi 产品平面平行重写压缩逻辑。仅当对话轮数超过 {@code contextWindow} 时触发 LLM 摘要，
 * 摘要前/后的记忆 flush 与原始消息 offload 均关闭（{@code flushBeforeCompact=false} /
 * {@code offloadBeforeCompact=false}），因此只需一个 {@code Model}、无需注入工作区管理器等运行时依赖。</p>
 *
 * <p>安全边界：模型不可用、LLM 调用超时或任意异常时一律返回 {@code null}，由调用方回退到朴素
 * 「最近 N 轮」窗口拼接文本，保证读路径不退化、不抛错。</p>
 */
@Component
@RequiredArgsConstructor
public class ContextSnapshotCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextSnapshotCompactor.class);

    private final ModelFactory modelFactory;
    private final AistioIntegrationProperties properties;

    /**
     * 对超窗口对话产出压缩上下文；未超窗口、未启用或任意失败时返回 {@code null}（调用方回退朴素窗口）。
     *
     * @param agentName  智能体名（仅用于压缩产物的 offload 路径标记，本组件关闭 offload 故仅作诊断）
     * @param sessionId 会话 ID（同上）
     * @param rawMessages 对话历史原始消息（{@code Map} 形态，含 {@code role}/{@code content}）
     * @return 压缩后的上下文快照，或 {@code null}
     */
    public ContextSnapshot compact(String agentName, String sessionId, List<?> rawMessages) {
        if (!properties.isContextCompactionEnabled()) {
            return null;
        }
        if (rawMessages == null || rawMessages.size() <= properties.getContextWindow()) {
            return null;
        }

        io.agentscope.core.model.Model model;
        try {
            model = modelFactory.create(null);
        } catch (Exception e) {
            log.warn("context 压缩跳过：模型不可用（{}），回退朴素窗口", e.getMessage());
            return null;
        }

        List<Msg> msgs = toMessages(rawMessages);
        if (msgs.isEmpty()) {
            return null;
        }

        CompactionConfig cfg = CompactionConfig.builder()
                .triggerMessages(properties.getContextWindow())
                .keepMessages(properties.getContextWindow())
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .model(model)
                .build();

        ConversationCompactor compactor = new ConversationCompactor(model, null);
        try {
            Optional<List<Msg>> result = compactor
                    .compactIfNeeded(null, msgs, cfg, agentName, sessionId)
                    .block(Duration.ofSeconds(properties.getContextCompactionTimeoutSec()));
            if (result == null || result.isEmpty()) {
                return null;
            }
            String text = render(result.get());
            ContextSnapshot snapshot = new ContextSnapshot();
            snapshot.setContext(text);
            snapshot.setTokens(text.length() / 4);
            return snapshot;
        } catch (Exception e) {
            log.warn("context 压缩失败，回退朴素窗口：{}", e.getMessage());
            return null;
        }
    }

    /** 将原始 {@code Map} 消息转换为框架 {@link Msg}；跳过系统消息与空内容。 */
    private List<Msg> toMessages(List<?> raw) {
        List<Msg> out = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) o;
            String role = m.get("role") == null ? "user" : String.valueOf(m.get("role"));
            Object contentObj = m.get("content");
            String content = contentObj == null
                    ? ""
                    : (contentObj instanceof String s ? s : String.valueOf(contentObj));
            if (content.isBlank()) {
                continue;
            }
            MsgRole r = mapRole(role);
            if (r == MsgRole.SYSTEM) {
                continue;
            }
            Msg.Builder b = Msg.builder().role(r);
            if (r == MsgRole.TOOL) {
                b.content(ToolResultBlock.builder()
                        .name(role)
                        .output(List.of(TextBlock.builder().text(content).build()))
                        .build());
            } else {
                b.content(TextBlock.builder().text(content).build());
            }
            out.add(b.build());
        }
        return out;
    }

    private static MsgRole mapRole(String role) {
        return switch (role.toLowerCase()) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "tool", "tool_result", "function" -> MsgRole.TOOL;
            default -> MsgRole.USER;
        };
    }

    /** 将压缩后的 {@link Msg} 列表渲染为与朴素窗口一致的 {@code role: content} 文本。 */
    private static String render(List<Msg> msgs) {
        StringBuilder sb = new StringBuilder();
        for (Msg msg : msgs) {
            String role = msg.getRole() == null
                    ? "unknown"
                    : switch (msg.getRole()) {
                        case USER -> "user";
                        case ASSISTANT -> "assistant";
                        case TOOL -> "tool";
                        case SYSTEM -> "system";
                        default -> msg.getRole().name().toLowerCase();
                    };
            sb.append(role).append(": ");
            if (msg.getContent() != null) {
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof TextBlock tb && tb.getText() != null) {
                        sb.append(tb.getText());
                    } else if (block instanceof ToolResultBlock tr) {
                        sb.append("[tool_result: ")
                                .append(tr.getName() == null ? "?" : tr.getName())
                                .append("] ");
                        if (tr.getOutput() != null) {
                            for (ContentBlock ob : tr.getOutput()) {
                                if (ob instanceof TextBlock tb && tb.getText() != null) {
                                    sb.append(tb.getText());
                                }
                            }
                        }
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
