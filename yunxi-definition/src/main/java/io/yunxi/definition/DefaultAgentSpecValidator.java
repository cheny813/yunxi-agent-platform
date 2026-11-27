package io.yunxi.definition;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 默认 {@link AgentSpecValidator}：强制满足最低的「身份 + 权限」契约。
 * 交叉引用完整性（例如被引用的技能是否真实存在）由能力平面在后续阶段校验；本校验器只守护结构上的合法性。
 *
 * <p>权限之所以与身份同为必填，是因为缺失的代价不对称：身份缺失会让 Agent 无法被寻址（立即暴露），
 * 权限缺失则让代码替作者猜一个模式——可能全拒（Agent 静默失去全部业务工具），也可能全放行
 * （限制根本没生效），而两种结果都不会产生任何报错。
 */
public class DefaultAgentSpecValidator implements AgentSpecValidator {

    /** 校验最低契约：非 null、identity/agentId/name 齐备、permission 显式声明；交叉引用完整性交给能力平面后续阶段。 */
    @Override
    public AgentSpec.ValidationResult validate(AgentSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null) {
            return AgentSpec.ValidationResult.fail(List.of("agentSpec is null"));
        }
        if (spec.identity() == null) {
            errors.add("identity is required");
        } else {
            if (isBlank(spec.identity().agentId())) {
                errors.add("identity.agentId is required");
            }
            if (isBlank(spec.identity().name())) {
                errors.add("identity.name is required");
            }
        }
        validatePermission(spec.permission(), errors);
        if (spec.schedule() != null) {
            AgentSpec.ScheduleConfig sc = spec.schedule();
            if (isBlank(sc.mode())
                    || !Set.of("CRON", "FIXED_RATE", "FIXED_DELAY", "NONE").contains(sc.mode())) {
                errors.add("schedule.mode must be one of CRON/FIXED_RATE/FIXED_DELAY/NONE");
            } else {
                switch (sc.mode()) {
                    case "CRON" -> {
                        if (isBlank(sc.cron())) {
                            errors.add("schedule.cron is required for CRON mode");
                        }
                    }
                    case "FIXED_RATE" -> {
                        if (sc.rateMillis() == null || sc.rateMillis() < AgentSpec.ScheduleConfig.MIN_INTERVAL_MILLIS) {
                            errors.add("schedule.rateMillis must be at least "
                                    + AgentSpec.ScheduleConfig.MIN_INTERVAL_MILLIS + " for FIXED_RATE mode");
                        }
                    }
                    case "FIXED_DELAY" -> {
                        if (sc.delayMillis() == null || sc.delayMillis() < AgentSpec.ScheduleConfig.MIN_INTERVAL_MILLIS) {
                            errors.add("schedule.delayMillis must be at least "
                                    + AgentSpec.ScheduleConfig.MIN_INTERVAL_MILLIS + " for FIXED_DELAY mode");
                        }
                    }
                    default -> { }
                }
            }
            if (sc.initialDelayMillis() != null && sc.initialDelayMillis() < 0) {
                errors.add("schedule.initialDelayMillis must not be negative");
            }
            if (!isBlank(sc.zoneId())) {
                try {
                    ZoneId.of(sc.zoneId());
                } catch (RuntimeException e) {
                    errors.add("schedule.zoneId must be a valid zone id");
                }
            }
        }
        if (spec.sandbox() != null) {
            AgentSpec.SandboxConfig sb = spec.sandbox();
            if (isBlank(sb.backend())) {
                errors.add("sandbox.backend is required");
            } else if (!"docker".equals(sb.backend())) {
                errors.add("sandbox.backend must be 'docker'");
            }
            if (sb.isolationScope() != null
                    && !Set.of("SESSION", "USER", "AGENT", "GLOBAL").contains(sb.isolationScope())) {
                errors.add("sandbox.isolationScope must be one of SESSION/USER/AGENT/GLOBAL");
            }
            if (sb.snapshot() != null && !"noop".equals(sb.snapshot().type())) {
                errors.add("sandbox.snapshot.type must be 'noop' in v1");
            }
        }
        validateGenerate(spec.generate(), errors);

        return errors.isEmpty() ? AgentSpec.ValidationResult.ok() : AgentSpec.ValidationResult.fail(errors);
    }

    /**
     * generate.extra-body 形状门禁：条目逐键透传到推理服务请求体，空白键匹配不到任何服务端约定
     * （「配了但没生效」），null 值会把无效载荷打进请求体。键值语义（如 enable_thinking 是否合法）
     * 由目标服务解释，这里只守护形状，不解释含义。
     */
    private static void validateGenerate(AgentSpec.GenerateConfig generate, List<String> errors) {
        if (generate == null || generate.extraBody() == null) {
            return;
        }
        generate.extraBody().forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                errors.add("generate.extra-body must not contain blank keys");
            }
            if (value == null) {
                errors.add("generate.extra-body." + key + " must not have a null value");
            }
        });
    }

    /**
     * 权限段门禁：把三类「意图与结果不一致」的配置挡在装配之前。
     *
     * <p>{@code permission} 是<b>必填</b>而非可选：缺省时只能由代码替作者猜模式，猜成哪一种都不体现在报错里
     * ——作者没写不等于作者同意，只有显式声明才算数。
     *
     * <p>各列表之间的<b>交叉一律拒绝，不做优先级裁决</b>：无论自动选哪一方，另一方写下的意图都会被静默
     * 丢弃，而作者只会看到「配了但没生效」。让人在定义期改对，比让代码在运行期猜更可靠。
     */
    private static void validatePermission(AgentSpec.PermissionConfig permission, List<String> errors) {
        if (permission == null) {
            errors.add("permission is required: 必须显式声明工具权限策略"
                    + "（allowedTools 白名单 / deniedTools 黑名单 / toolGate 人工确认）；"
                    + "缺失时只能由代码替作者猜模式，猜成全拒或全放行都不会报错");
            return;
        }
        List<String> allowed = nullToEmpty(permission.allowedTools());
        List<String> denied = nullToEmpty(permission.deniedTools());
        List<String> ask = permission.askTools();

        if (ask.isEmpty()) {
            if (permission.allowedTools() == null && permission.deniedTools() == null) {
                errors.add("permission must declare a tool policy: 至少声明 allowedTools（白名单）"
                        + "或 deniedTools（黑名单）之一；空壳配置无法判断无人值守下的默认行为");
            } else if (permission.allowedTools() != null && permission.deniedTools() != null) {
                errors.add("permission.allowedTools and permission.deniedTools must not be declared together"
                        + " without toolGate: 白名单与黑名单对「未列出的工具」语义相反（拒绝 / 放行），"
                        + "同时声明必然丢弃其中一半意图；需要两种语义共存请启用 toolGate 转为人工确认模式");
            }
        }
        reportBlankToolNames(errors, "allowedTools", allowed);
        reportBlankToolNames(errors, "deniedTools", denied);
        reportBlankToolNames(errors, "toolGate.tools", ask);
        reportOverlap(errors, "allowedTools", allowed, "deniedTools", denied);
        reportOverlap(errors, "allowedTools", allowed, "toolGate.tools", ask);
        reportOverlap(errors, "deniedTools", denied, "toolGate.tools", ask);
    }

    /**
     * 清单里出现空工具名（YAML 写成 {@code - } 或 {@code [null]}）即错误：这类条目匹配不到任何工具，
     * 规则永不命中，属于「写了却没生效」。顺带保证校验器不因 null 元素抛 NPE。
     */
    private static void reportBlankToolNames(List<String> errors, String field, List<String> tools) {
        if (tools.stream().anyMatch(t -> t == null || t.isBlank())) {
            errors.add("permission." + field + " must not contain blank tool names");
        }
    }

    /** 两个工具列表相交即错误：同一工具被要求「放行」与「拒绝/确认」是矛盾的，不能靠优先级静默取一方。 */
    private static void reportOverlap(List<String> errors, String leftName, List<String> left,
            String rightName, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return;
        }
        Set<String> rightSet = new HashSet<>(right);
        List<String> overlap = left.stream().filter(rightSet::contains).distinct().toList();
        if (!overlap.isEmpty()) {
            errors.add("permission." + leftName + " and permission." + rightName
                    + " must not share tools: " + String.join(", ", overlap));
        }
    }

    private static List<String> nullToEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }

    /** 工具方法：判断字符串为 null 或全空白。 */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
