package io.yunxi.platform.agent.scheduler;

import lombok.Data;

/**
 * 单个定时 Agent 的定义（对应 config/scheduler.yml 中 agents 列表的一项）。
 *
 * <p>设计边界：框架 {@code QuartzAgentScheduler} 每次触发都会用 {@code RuntimeAgentConfig}
 * 现造一个 Agent 实例，且配置类无 agentFactory 注入钩子，因此定时 Agent 与在线聊天 Agent
 * 是<b>两套独立种群</b>——本定义不引用在线 Agent 实例，仅描述「用什么模型、什么提示词、什么节奏」运行，
 * 从而避免双份 Agent 构建真相源。模型凭据优先复用 yunxi 集中配置 {@code AgentscopeCoreProperties}，
 * 不在本文件重复声明密钥。</p>
 */
@Data
public class ScheduledAgentDefinition {

    /** 定时 Agent 的唯一名称（同时作为 Quartz 任务标识），必填 */
    private String name;

    /** 是否启用，默认 true */
    private boolean enabled = true;

    /** 模型名称；为空时回落到 AgentscopeCoreProperties.modelName */
    private String modelName;

    /** 模型 API 密钥；为空时回落到 AgentscopeCoreProperties.apiKey */
    private String apiKey;

    /** 系统提示词；为空时使用默认的周期执行提示词 */
    private String sysPrompt;

    /** Cron 表达式（秒 分 时 日 月 周），与 fixedRateMs/fixedDelayMs 三选一 */
    private String cron;

    /** 固定频率触发间隔（毫秒） */
    private Long fixedRateMs;

    /** 固定延迟触发间隔（毫秒，上次结束后计时） */
    private Long fixedDelayMs;

    /** 首次触发前的初始延迟（毫秒），默认 0 */
    private Long initialDelayMs = 0L;

    /** 时区 ID（如 Asia/Shanghai），为空时使用 JVM 默认时区 */
    private String zoneId;
}
