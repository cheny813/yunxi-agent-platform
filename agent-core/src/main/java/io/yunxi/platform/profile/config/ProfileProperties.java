package io.yunxi.platform.profile.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户画像抽取的配置（前缀 {@code yunxi.profile}）。
 *
 * <p>画像抽取是「单次结构化抽取」轻任务，应与对话大模型解耦：走一个更便宜、更快的
 * 专用模型，并把输入长度封顶（滚动窗口），避免每轮重发整段历史导致 token 二次膨胀。
 * 默认取值偏向低成本、低延迟，部署方可经 profile.yml 或 {@code -D} 覆盖。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
@ConfigurationProperties(prefix = "yunxi.profile")
public class ProfileProperties {

    private final Llm llm = new Llm();
    private final Evolution evolution = new Evolution();

    public Llm getLlm() {
        return llm;
    }

    public Evolution getEvolution() {
        return evolution;
    }

    /**
     * 画像演进（自动触发）配置。
     *
     * <p>画像抽取挂载在调用收尾阶段（{@code onAgent}）异步执行，不阻塞用户响应。
     * 为避免每轮都抽导致 token 成本随轮数线性增长，提供节流：距上次抽取不足
     * {@code minIntervalSeconds} 或轮次增量不足 {@code minTurns} 时跳过本次触发。
     * 两个条件都为 0 表示不做节流（每轮都抽）。</p>
     */
    public static class Evolution {

        /**
         * 是否启用调用收尾自动抽取。默认开启；关闭后仅保留手动 evolve 端点。
         */
        private boolean enabled = true;

        /**
         * 节流：距上次抽取的最小间隔（秒）。默认 60；设为 0 表示不按时间节流。
         */
        private long minIntervalSeconds = 60L;

        /**
         * 节流：距上次抽取需累积的最小轮次数。默认 3；设为 0 表示不按轮次节流。
         *
         * <p>时间与轮次为「或」关系：任一条件达成即触发，便于低频场景也能及时落库。</p>
         */
        private int minTurns = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getMinIntervalSeconds() {
            return minIntervalSeconds;
        }

        public void setMinIntervalSeconds(long minIntervalSeconds) {
            this.minIntervalSeconds = minIntervalSeconds;
        }

        public int getMinTurns() {
            return minTurns;
        }

        public void setMinTurns(int minTurns) {
            this.minTurns = minTurns;
        }
    }

    public static class Llm {

        /**
         * 抽取专用模型。默认 qwen-turbo 级快模型；与对话模型（agentscope.core.model-name）
         * 完全独立，经 {@code -DPROFILE_MODEL=...} 即可切换，不污染对话链路。
         */
        private String model = "qwen-turbo";

        /**
         * 单次抽取超时（毫秒）。默认 2000，超过即降级为「不更新画像」。
         */
        private long timeoutMs = 2000L;

        /**
         * 输入滚动窗口：只取最近 N 条消息拼给模型，封住每轮 token 上限。默认 16。
         */
        private int maxWindowMessages = 16;

        /**
         * 输入滚动窗口：拼接文本超过该字符数时，只保留尾部，确保输入 token 有硬上限。默认 4000。
         */
        private int maxWindowChars = 4000;

        /**
         * 抽取是确定性任务，固定 0 温度，结果可复现。
         */
        private Double temperature = 0.0;

        /**
         * 输出 token 硬上限。画像 JSON 很小，600 足够，压住输出成本。
         */
        private Integer maxTokens = 600;

        /**
         * 低于该置信度的单条身份不纳入画像（值级兜底，防脏数据；不影响降级安全网）。
         */
        private double minConfidence = 0.3;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxWindowMessages() {
            return maxWindowMessages;
        }

        public void setMaxWindowMessages(int maxWindowMessages) {
            this.maxWindowMessages = maxWindowMessages;
        }

        public int getMaxWindowChars() {
            return maxWindowChars;
        }

        public void setMaxWindowChars(int maxWindowChars) {
            this.maxWindowChars = maxWindowChars;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }
    }
}
