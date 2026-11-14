package io.yunxi.definition;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 默认 {@link AgentSpecValidator}：强制满足最低的「身份」契约。
 * 交叉引用完整性（例如被引用的技能是否真实存在）由能力平面在后续阶段校验；本校验器只守护结构上的合法性。
 */
public class DefaultAgentSpecValidator implements AgentSpecValidator {

    /** 校验最低身份契约：非 null、identity/agentId/name 齐备；交叉引用完整性交给能力平面后续阶段。 */
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

        return errors.isEmpty() ? AgentSpec.ValidationResult.ok() : AgentSpec.ValidationResult.fail(errors);
    }

    /** 工具方法：判断字符串为 null 或全空白。 */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
