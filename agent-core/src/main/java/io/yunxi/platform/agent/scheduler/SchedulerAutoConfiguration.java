package io.yunxi.platform.agent.scheduler;

import io.agentscope.extensions.scheduler.config.DashScopeModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.quartz.QuartzAgentScheduler;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import jakarta.annotation.PostConstruct;

import java.io.InputStream;

/**
 * 框架定时调度能力的统一入口接线。
 *
 * <p>职责仅是把 yunxi 既有的 {@code config/scheduler.yml} 中声明的定时 Agent 注册到框架
 * {@code QuartzAgentScheduler}，不重写调度语义、不重复构建 Agent 真相源。</p>
 *
 * <h3>与在线 Agent 的关系（避免重复的真相源）</h3>
 * 框架 {@code QuartzAgentScheduler} 每次触发都会用 {@code RuntimeAgentConfig} 现造一个 Agent 实例，
 * 且配置类无 agentFactory 注入钩子（2.0.4 源码核实：{@code AgentConfig}/{@code RuntimeAgentConfig}
 * 均无该字段，{@code QuartzAgentScheduler} javadoc 中的 {@code .agentFactory(...)} 为过时写法）。
 * 因此定时 Agent 与 {@code AgentConfigurer} 构建的在线 Agent 是两套独立种群：本配置只描述
 * 「模型 + 提示词 + 节奏」，模型凭据复用 {@link AgentscopeCoreProperties}，不引用在线 Agent 实例，
 * 也不承担在线 Agent 的 middleware 链（压缩 / 画像 / 权限等由在线路径独立装配）。</p>
 *
 * <p>单个定时 Agent 注册失败只记录日志并继续，不阻断应用启动。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "yunxi.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerAutoConfiguration {

    private static final String DEFAULT_SYS_PROMPT =
            "你是一个按既定节奏周期性自主执行的 Agent。请根据当前上下文完成本周期的既定任务，"
                    + "输出简洁、可落地的执行结果。";

    private final AgentscopeCoreProperties coreProperties;
    private final QuartzAgentScheduler agentScheduler;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Value("${yunxi.scheduler.config-path:classpath*:config/scheduler.yml}")
    private String configPathPattern;

    public SchedulerAutoConfiguration(AgentscopeCoreProperties coreProperties) {
        this.coreProperties = coreProperties;
        this.agentScheduler = QuartzAgentScheduler.builder().autoStart(true).build();
    }

    /**
     * 容器启动后加载 config/scheduler.yml 并注册全部启用的定时 Agent。
     */
    @PostConstruct
    public void registerSchedules() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(configPathPattern);
            int registered = 0;
            for (Resource resource : resources) {
                if (!resource.exists()) {
                    continue;
                }
                registered += registerFrom(resource);
            }
            log.info("[scheduler] 定时 Agent 注册完成，本次共注册 {} 个", registered);
        } catch (Exception e) {
            log.error("[scheduler] 加载定时 Agent 配置失败", e);
        }
    }

    private int registerFrom(Resource resource) {
        int count = 0;
        try (InputStream in = resource.getInputStream()) {
            SchedulerDefinitionWrapper wrapper = yamlMapper.readValue(in, SchedulerDefinitionWrapper.class);
            if (wrapper == null || wrapper.getAgents() == null) {
                return 0;
            }
            for (ScheduledAgentDefinition def : wrapper.getAgents()) {
                if (def == null || !def.isEnabled()) {
                    continue;
                }
                if (registerOne(def)) {
                    count++;
                }
            }
        } catch (Exception e) {
            log.error("[scheduler] 解析定时 Agent 配置文件失败: {}", resource.getFilename(), e);
        }
        return count;
    }

    boolean registerOne(ScheduledAgentDefinition def) {
        if (def.getName() == null || def.getName().isBlank()) {
            log.warn("[scheduler] 跳过未命名定时 Agent 定义");
            return false;
        }
        if (resolveTrigger(def) == TriggerKind.NONE) {
            log.warn("[scheduler] 跳过定时 Agent {}：未配置 cron / fixedRateMs / fixedDelayMs 任一触发节奏",
                    def.getName());
            return false;
        }
        RuntimeAgentConfig agentConfig = buildAgentConfig(def);
        ScheduleConfig scheduleConfig = buildScheduleConfig(def);

        try {
            agentScheduler.schedule(agentConfig, scheduleConfig);
            log.info("[scheduler] 已注册定时 Agent: {}（模型={}, 节奏={}）", def.getName(),
                    resolveAgentConfig(def).modelName(), describeMode(def));
            return true;
        } catch (Exception e) {
            log.error("[scheduler] 注册定时 Agent 失败: {}", def.getName(), e);
            return false;
        }
    }

    /**
     * 触发方式枚举，显式表达 cron / fixedRate / fixedDelay 的优先级，便于单测无框架依赖地验证选择逻辑。
     */
    enum TriggerKind {
        CRON, FIXED_RATE, FIXED_DELAY, NONE
    }

    /**
     * 触发方式选择（cron 优先，回落 fixedRate，再回落 fixedDelay，皆无则 NONE）。
     * 抽成不依赖框架类型的方法，使优先级选择可被单测直接断言。
     */
    TriggerKind resolveTrigger(ScheduledAgentDefinition def) {
        if (def.getCron() != null && !def.getCron().isBlank()) {
            return TriggerKind.CRON;
        }
        if (def.getFixedRateMs() != null) {
            return TriggerKind.FIXED_RATE;
        }
        if (def.getFixedDelayMs() != null) {
            return TriggerKind.FIXED_DELAY;
        }
        return TriggerKind.NONE;
    }

    /**
     * 凭据 / 系统提示词的回落结果。抽成不依赖框架类型的方法，使回落逻辑可被单测直接断言。
     */
    record ResolvedAgentConfig(String apiKey, String modelName, String sysPrompt) {
    }

    ResolvedAgentConfig resolveAgentConfig(ScheduledAgentDefinition def) {
        String apiKey = (def.getApiKey() == null || def.getApiKey().isBlank())
                ? coreProperties.getApiKey() : def.getApiKey();
        String modelName = (def.getModelName() == null || def.getModelName().isBlank())
                ? coreProperties.getModelName() : def.getModelName();
        String sysPrompt = (def.getSysPrompt() == null || def.getSysPrompt().isBlank())
                ? DEFAULT_SYS_PROMPT : def.getSysPrompt();
        return new ResolvedAgentConfig(apiKey, modelName, sysPrompt);
    }

    RuntimeAgentConfig buildAgentConfig(ScheduledAgentDefinition def) {
        ResolvedAgentConfig r = resolveAgentConfig(def);
        return RuntimeAgentConfig.builder()
                .name(def.getName())
                .modelConfig(DashScopeModelConfig.builder().apiKey(r.apiKey()).modelName(r.modelName()).build())
                .sysPrompt(r.sysPrompt())
                .build();
    }

    ScheduleConfig buildScheduleConfig(ScheduledAgentDefinition def) {
        ScheduleConfig.Builder builder = ScheduleConfig.builder();
        switch (resolveTrigger(def)) {
            case CRON -> builder.cron(def.getCron());
            case FIXED_RATE -> builder.fixedRate(def.getFixedRateMs());
            case FIXED_DELAY -> builder.fixedDelay(def.getFixedDelayMs());
            case NONE -> {
                return null;
            }
        }
        builder.initialDelay(def.getInitialDelayMs() != null ? def.getInitialDelayMs() : 0L);
        if (def.getZoneId() != null && !def.getZoneId().isBlank()) {
            builder.zoneId(def.getZoneId());
        }
        return builder.build();
    }

    private String describeMode(ScheduledAgentDefinition def) {
        if (def.getCron() != null && !def.getCron().isBlank()) {
            return "cron=" + def.getCron();
        }
        if (def.getFixedRateMs() != null) {
            return "fixedRate=" + def.getFixedRateMs() + "ms";
        }
        return "fixedDelay=" + def.getFixedDelayMs() + "ms";
    }
}
