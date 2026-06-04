package io.yunxi.platform.framework.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 提示注入防护 Hook — 基于框架 {@link Hook} 接口实现
 * <p>
 * 在模型推理完成后（{@link PostReasoningEvent}），检查推理消息中是否包含
 * 提示注入模式。检测到注入时调用 {@link PostReasoningEvent#stopAgent()} 停止 Agent。
 * </p>
 *
 * <p>
 * 注入模式（默认，中英文）：
 * <ul>
 * <li>忽略之前指令（ignore previous instructions）</li>
 * <li>角色扮演/系统提示覆盖（you are now / 角色切换）</li>
 * <li>越狱提示（DAN/jailbreak）</li>
 * </ul>
 * </p>
 *
 * <p>
 * 使用方式：在 AgentConfigurer 中注入到 HarnessAgent.Builder：
 * 
 * <pre>{@code
 * builder.hook(new ContentFilterHook());
 * }</pre>
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class ContentFilterHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ContentFilterHook.class);

    /** 注入检测模式列表 */
    private final List<Pattern> dangerousPatterns;

    /** 优先级：安全类 Hook 使用高优先级（50） */
    private final int priority;

    public ContentFilterHook() {
        this(50);
    }

    public ContentFilterHook(int priority) {
        this.dangerousPatterns = getDefaultPatterns();
        this.priority = priority;
    }

    /**
     * 获取默认检测模式（中英文覆盖）
     */
    private static List<Pattern> getDefaultPatterns() {
        return List.of(
                // 英文注入模式
                Pattern.compile("(?i)ignore\\s+(all\\s+)?previous\\s+instructions"),
                Pattern.compile("(?i)(system|new)\\s*:\\s*"),
                Pattern.compile("(?i)^you\\s+are\\s+now\\s+"),
                Pattern.compile("(?i)^from\\s+now\\s+on\\s+"),
                Pattern.compile("(?i)^act\\s+as\\s+"),
                Pattern.compile("(?i)^do\\s+(not|n't)\\s+(follow|obey|listen)"),
                Pattern.compile("(?i)^override\\s+(mode|system|prompt)"),
                Pattern.compile("(?i)^DAN\\b|^jailbreak"),

                // 中文注入模式
                Pattern.compile("忽略\\s*(所有\\s+)?(之前|以前|以上)\\s*(的\\s+)?(指令|指示|设定|要求)"),
                Pattern.compile("(系统|新)\\s*[：:]\\s*"),
                Pattern.compile("^(从现在起|从现在开始|接下来)\\s+"),
                Pattern.compile("^(扮演|假装|作为)\\s+"),
                Pattern.compile("^(不要|别)\\s+(遵循|遵守|听从|按照)"),
                Pattern.compile("^(覆盖|绕过|跳过).*(模式|系统|提示)"));
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // 在模型推理完成后检查注入
        if (event instanceof PostReasoningEvent postReasoning) {
            Msg reasoningMsg = postReasoning.getReasoningMessage();
            if (reasoningMsg == null) {
                return Mono.just(event);
            }

            // 提取推理消息中所有文本内容进行检查
            String textContent = reasoningMsg.getTextContent();
            if (textContent != null && containsInjection(textContent)) {
                log.warn("检测到潜在的提示注入，已拦截: {}", truncate(textContent, 120));
                postReasoning.stopAgent();
                return Mono.just(event);
            }

            // 同时检查工具调用参数中是否包含注入
            List<ToolUseBlock> toolCalls = reasoningMsg.getContentBlocks(ToolUseBlock.class);
            for (ToolUseBlock toolCall : toolCalls) {
                // getInput() 返回 Map，转为字符串做注入检测
                String input = String.valueOf(toolCall.getInput());
                if (input != null && !input.isBlank() && !"null".equals(input) && containsInjection(input)) {
                    log.warn("工具调用参数中检测到提示注入，已拦截: tool={}, input={}",
                            toolCall.getName(), truncate(input, 80));
                    postReasoning.stopAgent();
                    return Mono.just(event);
                }
            }
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return priority;
    }

    /**
     * 检查文本是否匹配任意注入模式
     */
    private boolean containsInjection(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        for (Pattern pattern : dangerousPatterns) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 截断长文本用于日志输出
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
