package io.yunxi.runtime.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.runtime.skilluse.SkillUseEvent;
import io.yunxi.runtime.skilluse.SkillUseEventStore;
import io.yunxi.runtime.skilluse.SkillUseEventType;
import io.yunxi.runtime.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 技能使用事件记录中间件：在工具执行点拦截技能加载/使用调用，落事件到共享存储。
 *
 * <p>拦截口径与框架 SkillUsageMiddleware 一致：VIEW = load_skill_through_path / read_skill，
 * USE = use_skill。记录语义为「调用意向」（onActing 进入即记，不等待调用结果）。
 * 记录为 best-effort——任何失败只打 DEBUG 日志，绝不中断 agent 推理循环。
 *
 * <p>归属：userId 取自 RuntimeContext（per-call），tenantId 取自 TenantContext（admit 绑定）。
 */
public class SkillUseEventRecorder implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SkillUseEventRecorder.class);

    /** 技能内容加载类工具 → VIEW。 */
    private static final Set<String> VIEW_TOOLS = Set.of("load_skill_through_path", "read_skill");
    /** 显式技能调用工具 → USE。 */
    private static final Set<String> USE_TOOLS = Set.of("use_skill");

    private final SkillUseEventStore store;

    public SkillUseEventRecorder(SkillUseEventStore store) {
        this.store = store;
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return Set.of(ExtensionPoint.ON_ACTING);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        recordEvents(input.toolCalls(), ctx);
        return next.apply(input);
    }

    private void recordEvents(List<ToolUseBlock> toolCalls, RuntimeContext ctx) {
        List<SkillUseEvent> events = extractEvents(toolCalls, ctx);
        for (SkillUseEvent event : events) {
            try {
                store.record(event);
            } catch (Exception e) {
                // 遥测是旁路：记录失败不影响本轮工具执行（与框架 SkillUsageMiddleware 同语义）
                log.debug("[技能使用] 记录事件失败（忽略）: skill={}, cause={}", event.skill(), e.getMessage());
            }
        }
    }

    /** 纯函数：从工具调用提取事件；无 skillId 参数的调用不记录。 */
    private List<SkillUseEvent> extractEvents(List<ToolUseBlock> toolCalls, RuntimeContext ctx) {
        List<SkillUseEvent> events = new ArrayList<>();
        for (ToolUseBlock call : toolCalls) {
            SkillUseEventType type = classify(call.getName());
            if (type == null) {
                continue;
            }
            String skill = skillIdFrom(call.getInput());
            if (skill == null || skill.isBlank()) {
                continue;
            }
            events.add(new SkillUseEvent(skill, TenantContext.current(), ctx != null ? ctx.getUserId() : null, type, null));
        }
        return events;
    }

    private SkillUseEventType classify(String toolName) {
        if (VIEW_TOOLS.contains(toolName)) {
            return SkillUseEventType.VIEW;
        }
        if (USE_TOOLS.contains(toolName)) {
            return SkillUseEventType.USE;
        }
        return null;
    }

    private String skillIdFrom(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        Object skillId = input.get("skillId");
        if (skillId == null) {
            skillId = input.get("skill_id");
        }
        if (skillId == null) {
            skillId = input.get("name");
        }
        return skillId != null ? String.valueOf(skillId) : null;
    }
}
