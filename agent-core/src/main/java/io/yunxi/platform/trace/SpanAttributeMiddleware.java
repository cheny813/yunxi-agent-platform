package io.yunxi.platform.trace;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;

/**
 * 轨迹属性补充中间件。
 *
 * <p>把只存在于中间件入参、事件流里没有的信息写入轨迹属性袋，供归集层合并进轨迹节点。
 * 覆盖两类信息：模型调用的模型与提供方，以及技能调用命中的技能归属。</p>
 *
 * <p>两类信息的共同点是：框架事件本身不携带来源细节，但框架自身的可观测实现都在中间件侧
 * 取用入参。此中间件因此只做读取与登记，不改变入参与事件内容，失败也不影响执行。</p>
 *
 * @author yunxi-agent-platform
 */
public class SpanAttributeMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SpanAttributeMiddleware.class);

    /**
     * 调用即视为「查看技能」的工具名。
     *
     * <p>与框架的技能使用计数口径保持一致，使技能归因与实际计数覆盖同一批工具。</p>
     */
    private static final Set<String> VIEW_TOOL_NAMES = Set.of("load_skill_through_path", "read_skill");

    /**
     * 调用即视为「使用技能」的工具名。
     */
    private static final Set<String> USE_TOOL_NAMES = Set.of("use_skill");

    /** 技能标识在工具入参中的候选键，按优先级排列 */
    private static final String[] SKILL_ID_KEYS = {"skillId", "skill_id", "name"};

    @Override
    public int order() {
        return 900;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        try {
            Model model = input != null ? input.model() : null;
            if (model != null) {
                SpanAttributeBag.of(ctx).putModel(model.getModelName(), providerOf(model));
            }
        } catch (Exception e) {
            log.debug("模型属性登记失败: {}", e.getMessage());
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        try {
            if (input != null && input.toolCalls() != null) {
                SpanAttributeBag bag = SpanAttributeBag.of(ctx);
                for (ToolUseBlock call : input.toolCalls()) {
                    registerSkill(bag, call);
                }
            }
        } catch (Exception e) {
            log.debug("技能属性登记失败: {}", e.getMessage());
        }
        return next.apply(input);
    }

    /**
     * 登记一次技能命中：调用即计入，与框架的技能使用计数口径一致。
     */
    private void registerSkill(SpanAttributeBag bag, ToolUseBlock call) {
        if (call == null || call.getName() == null) {
            return;
        }
        String toolName = call.getName();
        boolean isView = VIEW_TOOL_NAMES.contains(toolName);
        boolean isUse = USE_TOOL_NAMES.contains(toolName);
        if (!isView && !isUse) {
            return;
        }
        String skillName = extractSkillName(call);
        if (skillName == null || skillName.isBlank()) {
            return;
        }
        bag.putSkill(call, skillName, isView ? "view" : "use");
    }

    /**
     * 从工具入参中提取技能标识。
     */
    private static String extractSkillName(ToolUseBlock call) {
        Map<String, Object> input = call.getInput();
        if (input == null) {
            return null;
        }
        for (String key : SKILL_ID_KEYS) {
            Object value = input.get(key);
            if (value != null) {
                String text = value.toString().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * 推导模型提供方。
     *
     * <p>由模型类名推导：类名形如「提供方名 + Model」时取前半段作为提供方标识。该推导依赖
     * 实现类的命名约定，遇到代理类或装饰类会得到无意义的结果，因此对结果做形状校验 ——
     * 只接受纯标识符形式（字母数字与下划线），其余一律返回 null，由消费方按模型名回退。
     * 给出空值优于给出错误值。</p>
     */
    private static String providerOf(Model model) {
        String simpleName = model.getClass().getSimpleName();
        if (simpleName.endsWith("Model")) {
            simpleName = simpleName.substring(0, simpleName.length() - "Model".length());
        }
        return isPlainIdentifier(simpleName) ? simpleName : null;
    }

    /**
     * 判断字符串是否为纯标识符形式（非空，且仅含字母、数字、下划线）。
     */
    private static boolean isPlainIdentifier(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
