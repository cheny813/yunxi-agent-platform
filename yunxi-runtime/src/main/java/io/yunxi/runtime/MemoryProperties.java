package io.yunxi.runtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆策略配置（yunxi 对上下文压缩与跨轮次记忆留存负所有权）。
 *
 * <p>AgentScope-Java 2.1.0 的 {@code HarnessAgent.Builder} 提供 {@code compaction(CompactionConfig)}
 * 与 {@code memory(MemoryConfig)} 两个接线点；框架虽有默认策略，但 yunxi 作为平台必须显式拍板阈值，
 * 不可一直静默沿用框架默认。本类把这两类策略收敛到 {@code yunxi.memory.*} 前缀下，
 * 由 {@link AgentConfigurer} 在构建 Agent 时翻译为框架配置对象。</p>
 *
 * <p>默认值取自框架推荐基线，可按业务负载在 {@code config/memory.yml} 覆盖。</p>
 */
@ConfigurationProperties(prefix = "yunxi.memory")
public class MemoryProperties {

    /** 会话内上下文压缩策略：当对话超过阈值时，把早期消息摘要化以释放上下文窗口。 */
    private final Compaction compaction = new Compaction();

    /** 跨轮次记忆留存策略：工作区记忆文件的保留时长与合并节奏。 */
    private final Retention retention = new Retention();


    /** 返回压缩策略配置。 */
    public Compaction compaction() {
        return compaction;
    }

    /** 返回记忆留存配置。 */
    public Retention retention() {
        return retention;
    }


    /** 会话内上下文压缩策略（in-session compaction）。 */
    public static class Compaction {

        /** 累计多少轮对话后触发一次压缩。默认 50 轮。 */
        private int triggerMessages = 50;

        /** 压缩后保留的最小 token 数，保证摘要不至于过短丢失关键信息。默认 2000。 */
        private int keepTokensMin = 2000;

        /** 压缩后保留的最大 token 数，防止摘要膨胀挤占上下文。默认 8000。 */
        private int keepTokensMax = 8000;

        /** 相对上下文窗口的保留比例，与 keepTokensMin/Max 共同约束摘要长度。默认 0.25。 */
        private double keepTokensRatio = 0.25;

        /** 预留给系统提示词与工具 schema 的 token 预算，压缩时优先保留。默认 2000。 */
        private int reserved = 2000;

        /** 无论如何都保留的最近消息条数，保证当前轮次语义连贯。默认 20。 */
        private int keepMessages = 20;

        /** 压缩前是否先把本轮增量落盘，避免压缩过程中的状态丢失。默认 true。 */
        private boolean flushBeforeCompact = true;

        /** 返回触发压缩的累计轮次阈值。 */
        public int getTriggerMessages() {
            return triggerMessages;
        }

        /** 设置触发压缩的累计轮次阈值。 */
        public void setTriggerMessages(int triggerMessages) {
            this.triggerMessages = triggerMessages;
        }

        /** 返回压缩后保留的最小 token 数。 */
        public int getKeepTokensMin() {
            return keepTokensMin;
        }

        /** 设置压缩后保留的最小 token 数。 */
        public void setKeepTokensMin(int keepTokensMin) {
            this.keepTokensMin = keepTokensMin;
        }

        /** 返回压缩后保留的最大 token 数。 */
        public int getKeepTokensMax() {
            return keepTokensMax;
        }

        /** 设置压缩后保留的最大 token 数。 */
        public void setKeepTokensMax(int keepTokensMax) {
            this.keepTokensMax = keepTokensMax;
        }

        /** 返回相对上下文窗口的保留比例。 */
        public double getKeepTokensRatio() {
            return keepTokensRatio;
        }

        /** 设置相对上下文窗口的保留比例。 */
        public void setKeepTokensRatio(double keepTokensRatio) {
            this.keepTokensRatio = keepTokensRatio;
        }

        /** 返回预留 token 预算。 */
        public int getReserved() {
            return reserved;
        }

        /** 设置预留 token 预算。 */
        public void setReserved(int reserved) {
            this.reserved = reserved;
        }

        /** 返回始终保留的最近消息条数。 */
        public int getKeepMessages() {
            return keepMessages;
        }

        /** 设置始终保留的最近消息条数。 */
        public void setKeepMessages(int keepMessages) {
            this.keepMessages = keepMessages;
        }

        /** 返回压缩前是否先落盘。 */
        public boolean isFlushBeforeCompact() {
            return flushBeforeCompact;
        }

        /** 设置压缩前是否先落盘。 */
        public void setFlushBeforeCompact(boolean flushBeforeCompact) {
            this.flushBeforeCompact = flushBeforeCompact;
        }
    }

    /** 跨轮次记忆留存策略。 */
    public static class Retention {

        /** 每日记忆文件保留天数，到期由框架清理。默认 180 天。 */
        private int dailyFileRetentionDays = 180;

        /** 两次记忆合并（consolidation）之间的最小间隔，避免频繁合并浪费算力。默认 30 分钟。 */
        private int consolidationMinGapMinutes = 30;

        /** 单次合并处理的最大 token 数，超过则分批。默认 8000。 */
        private int consolidationMaxTokens = 8000;

        /** 记忆落盘触发模式：ALWAYS 每轮落盘、NEVER 不主动落盘、THROTTLED 按间隔节流。默认 ALWAYS。 */
        private FlushTriggerMode flushTrigger = FlushTriggerMode.ALWAYS;

        /** THROTTLED 模式下的节流间隔分钟数。默认 1 分钟。 */
        private int flushThrottleMinutes = 1;

        /** 返回每日记忆文件保留天数。 */
        public int getDailyFileRetentionDays() {
            return dailyFileRetentionDays;
        }

        /** 设置每日记忆文件保留天数。 */
        public void setDailyFileRetentionDays(int dailyFileRetentionDays) {
            this.dailyFileRetentionDays = dailyFileRetentionDays;
        }

        /** 返回两次合并的最小间隔分钟数。 */
        public int getConsolidationMinGapMinutes() {
            return consolidationMinGapMinutes;
        }

        /** 设置两次合并的最小间隔分钟数。 */
        public void setConsolidationMinGapMinutes(int consolidationMinGapMinutes) {
            this.consolidationMinGapMinutes = consolidationMinGapMinutes;
        }

        /** 返回单次合并的最大 token 数。 */
        public int getConsolidationMaxTokens() {
            return consolidationMaxTokens;
        }

        /** 设置单次合并的最大 token 数。 */
        public void setConsolidationMaxTokens(int consolidationMaxTokens) {
            this.consolidationMaxTokens = consolidationMaxTokens;
        }

        /** 返回记忆落盘触发模式。 */
        public FlushTriggerMode getFlushTrigger() {
            return flushTrigger;
        }

        /** 设置记忆落盘触发模式。 */
        public void setFlushTrigger(FlushTriggerMode flushTrigger) {
            this.flushTrigger = flushTrigger;
        }

        /** 返回 THROTTLED 模式的节流间隔分钟数。 */
        public int getFlushThrottleMinutes() {
            return flushThrottleMinutes;
        }

        /** 设置 THROTTLED 模式的节流间隔分钟数。 */
        public void setFlushThrottleMinutes(int flushThrottleMinutes) {
            this.flushThrottleMinutes = flushThrottleMinutes;
        }
    }

    /** 记忆落盘触发模式枚举，映射到 AgentScope-Java 的 {@code MemoryConfig.FlushTrigger}。 */
    public enum FlushTriggerMode {
        /** 每轮对话结束均落盘记忆。 */
        ALWAYS,
        /** 不主动落盘，由调用方显式触发。 */
        NEVER,
        /** 按固定间隔节流落盘。 */
        THROTTLED
    }


    /**
     * 把 {@link FlushTriggerMode} 翻译为 AgentScope-Java 框架的 {@code MemoryConfig.FlushTrigger} 实例。
     *
     * @param mode 本平台的落盘触发模式
     * @param throttleMinutes THROTTLED 模式下的最小间隔分钟数
     * @return 框架落盘触发器
     */
    public static io.agentscope.harness.agent.memory.MemoryConfig.FlushTrigger toFrameworkFlushTrigger(
            FlushTriggerMode mode, int throttleMinutes) {
        return switch (mode) {
            case ALWAYS -> io.agentscope.harness.agent.memory.MemoryConfig.FlushTrigger.always();
            case NEVER -> io.agentscope.harness.agent.memory.MemoryConfig.FlushTrigger.never();
            case THROTTLED -> io.agentscope.harness.agent.memory.MemoryConfig.FlushTrigger
                    .throttled(Duration.ofMinutes(throttleMinutes));
        };
    }
}
