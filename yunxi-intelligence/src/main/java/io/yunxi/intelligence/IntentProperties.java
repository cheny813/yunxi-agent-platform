package io.yunxi.intelligence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智能平面。意图引擎配置（{@code yunxi.intent.*}）。策略语义：
 * keyword = 仅关键词（旧行为，零模型调用）；llm = 模型判定为主（失败走关键词兜底）；
 * auto = 与 llm 相同的管线（关键词显式目标命中时短路，尊重运维声明）。
 */
@ConfigurationProperties(prefix = "yunxi.intent")
public class IntentProperties {

    /** 总开关；false 时不创建管线，解析一律默认兜底（与本次变更前行为一致）。 */
    private boolean enabled = true;

    /** 管线策略：keyword | llm | auto。 */
    private String strategy = "auto";

    /** 未显式指定 Agent 且无法判定时的兜底 Agent。 */
    private String defaultAgentId = "example-assistant";

    /** 模型判定置信度阈值，低于该值回落默认 Agent（标签保留）。 */
    private double minConfidence = 0.6d;

    /** 进入分类的候选上限。 */
    private int maxCandidates = 8;

    /** 送入分类模型的文本长度上限（字符）。 */
    private int maxInputChars = 2000;

    /** 关键词规则：意图标签 → 触发关键词列表。 */
    private Map<String, List<String>> rules = new LinkedHashMap<>();

    /** 关键词命中后的显式目标：意图标签 → 目标 Agent（缺省时以 defaultAgentId 兜底）。 */
    private Map<String, String> targets = new LinkedHashMap<>();

    /** 实体槽位正则（按名覆盖内置槽位：orderId / dateRange / amount / phone / email）。 */
    private Map<String, String> slots = new LinkedHashMap<>();

    /** 实体与改写开关。 */
    private Rewrite rewrite = new Rewrite();

    /** 分类器配置。 */
    private Classifier classifier = new Classifier();

    /** 实体改写配置。 */
    public static class Rewrite {
        /** 是否启用槽位抽取与实体改写。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** 分类器配置：实现选择与本地决策模型参数。 */
    public static class Classifier {
        /** 实现选择：local（默认，本地决策模型）| jev（可选增强，需 JEV_API_KEY）。 */
        private String impl = "local";

        /** 决策模型逻辑 id，经 ModelResolver 解析；"decision" 走 defaults.decision。 */
        private String modelId = "decision";

        /** 模型调用超时（毫秒）。 */
        private long timeoutMillis = 3000L;

        /** 单次判定最大生成 token。 */
        private int maxTokens = 512;

        /** 是否优先使用结构化输出（jsonSchema）；模型不支持时自动回退普通调用。 */
        private boolean structuredOutput = true;

        public String getImpl() {
            return impl;
        }

        public void setImpl(String impl) {
            this.impl = impl;
        }

        public String getModelId() {
            return modelId;
        }

        public void setModelId(String modelId) {
            this.modelId = modelId;
        }

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public boolean isStructuredOutput() {
            return structuredOutput;
        }

        public void setStructuredOutput(boolean structuredOutput) {
            this.structuredOutput = structuredOutput;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    public void setDefaultAgentId(String defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public int getMaxInputChars() {
        return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = maxInputChars;
    }

    public Map<String, List<String>> getRules() {
        return rules;
    }

    public void setRules(Map<String, List<String>> rules) {
        this.rules = rules;
    }

    public Map<String, String> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, String> targets) {
        this.targets = targets;
    }

    public Map<String, String> getSlots() {
        return slots;
    }

    public void setSlots(Map<String, String> slots) {
        this.slots = slots;
    }

    public Rewrite getRewrite() {
        return rewrite;
    }

    public void setRewrite(Rewrite rewrite) {
        this.rewrite = rewrite;
    }

    public Classifier getClassifier() {
        return classifier;
    }

    public void setClassifier(Classifier classifier) {
        this.classifier = classifier;
    }
}
