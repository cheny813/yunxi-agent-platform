package io.yunxi.platform.intent.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 意图引擎配置（前缀 yunxi.intent，业务层惯例，对齐 yunxi.muse.*）。
 *
 * <p>支持多域配置 {@code domains}/{@code resolver}；旧顶层字段
 * （ner-dictionary/terminology-table/intent-tree/mapping-table）保留为 base 域快捷方式，
 * 二者同时配置时以 {@code domains.base.*} 为准（零迁移）。另含分类配置
 * {@code classification}（含 llm/cache）与热更新配置 {@code reload}。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
@ConfigurationProperties(prefix = "yunxi.intent")
public class IntentProperties {

    /** 总开关（false = 仅场景模式，行为等价旧 SceneDetectionService） */
    private boolean enabled = true;

    /** NER 词典文件（base 域快捷方式，等价 domains.base.ner-dictionary） */
    private String nerDictionary = "classpath:intent/ner-dictionaries.yml";

    /** 改写启用 */
    private boolean rewriteEnabled = true;

    /** 改写处理器名列表（按序执行；不存在的处理器名 warn 跳过） */
    private List<String> rewriteProcessors = List.of("terminology");

    /** 术语表文件（base 域快捷方式） */
    private String terminologyTable = "classpath:intent/terminology.yml";

    /** 意图树文件（base 域快捷方式） */
    private String intentTree = "classpath:intent/intent-tree.yml";

    /** 映射表文件（base 域快捷方式） */
    private String mappingTable = "classpath:intent/intent-mapping.yml";

    /** 多域配置（key=域名；base 必在，缺省由旧顶层字段构造） */
    private Map<String, DomainSource> domains = new LinkedHashMap<>();

    /** 领域解析配置 */
    private Resolver resolver = new Resolver();

    /** 分类配置（rule|llm|hybrid） */
    private Classification classification = new Classification();

    /** 热更新配置 */
    private Reload reload = new Reload();

    /** 意图路由配置（识别→路由闭环，默认关闭渐进式上线） */
    private Routing routing = new Routing();

    /** 意图路由开关（便捷方法，等价 {@code getRouting().isEnabled()}） */
    public boolean isRoutingEnabled() {
        return routing != null && routing.isEnabled();
    }

    public Routing getRouting() {
        return routing;
    }

    public void setRouting(Routing routing) {
        this.routing = routing;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNerDictionary() {
        return nerDictionary;
    }

    public void setNerDictionary(String nerDictionary) {
        this.nerDictionary = nerDictionary;
    }

    public boolean isRewriteEnabled() {
        return rewriteEnabled;
    }

    public void setRewriteEnabled(boolean rewriteEnabled) {
        this.rewriteEnabled = rewriteEnabled;
    }

    public List<String> getRewriteProcessors() {
        return rewriteProcessors;
    }

    public void setRewriteProcessors(List<String> rewriteProcessors) {
        this.rewriteProcessors = rewriteProcessors;
    }

    public String getTerminologyTable() {
        return terminologyTable;
    }

    public void setTerminologyTable(String terminologyTable) {
        this.terminologyTable = terminologyTable;
    }

    public String getIntentTree() {
        return intentTree;
    }

    public void setIntentTree(String intentTree) {
        this.intentTree = intentTree;
    }

    public String getMappingTable() {
        return mappingTable;
    }

    public void setMappingTable(String mappingTable) {
        this.mappingTable = mappingTable;
    }

    public Map<String, DomainSource> getDomains() {
        return domains;
    }

    public void setDomains(Map<String, DomainSource> domains) {
        this.domains = domains;
    }

    public Resolver getResolver() {
        return resolver;
    }

    public void setResolver(Resolver resolver) {
        this.resolver = resolver;
    }

    public Classification getClassification() {
        return classification;
    }

    public void setClassification(Classification classification) {
        this.classification = classification;
    }

    public Reload getReload() {
        return reload;
    }

    public void setReload(Reload reload) {
        this.reload = reload;
    }

    /**
     * 意图路由配置。
     *
     * <p>配置前缀 {@code yunxi.intent.routing.*}。默认关闭，开启后 routeHint 参与
     * 会话入口的路由决策（advisory，失败/未命中保持原路由）。</p>
     */
    public static class Routing {

        /** 意图路由开关（默认 false，渐进式上线） */
        private boolean enabled = false;

        /** 最低采纳分数（RouteHint.score 低于此值不改道，默认 0.5） */
        private double minRouteScore = 0.5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getMinRouteScore() {
            return minRouteScore;
        }

        public void setMinRouteScore(double minRouteScore) {
            this.minRouteScore = minRouteScore;
        }
    }

    /**
     * 单域数据源。
     *
     * <p>业务域只列差异文件，缺省项继承 base（null 即继承）。
     * base 域由旧顶层字段快捷构造（此时四字段不 null）。</p>
     */
    public static class DomainSource {
        private String nerDictionary;
        private String terminologyTable;
        private String intentTree;
        private String mappingTable;

        public String getNerDictionary() {
            return nerDictionary;
        }

        public void setNerDictionary(String nerDictionary) {
            this.nerDictionary = nerDictionary;
        }

        public String getTerminologyTable() {
            return terminologyTable;
        }

        public void setTerminologyTable(String terminologyTable) {
            this.terminologyTable = terminologyTable;
        }

        public String getIntentTree() {
            return intentTree;
        }

        public void setIntentTree(String intentTree) {
            this.intentTree = intentTree;
        }

        public String getMappingTable() {
            return mappingTable;
        }

        public void setMappingTable(String mappingTable) {
            this.mappingTable = mappingTable;
        }
    }

    /**
     * 领域解析配置。
     *
     * <p>四层判定链：① 显式 {@code IntentContext.domain} → ② 规则匹配
     * （agent-prefixes 数组，agent 名前缀命中）→ ③ 配置 default → ④ base 兜底。
     * 规则支持 name+agent-prefixes+domain，可按需扩展（profile-in/user-domains 预留）。</p>
     */
    public static class Resolver {
        /** 默认域（未匹配规则时；缺省 base） */
        private String defaultDomain = "base";

        /** 规则列表（按序匹配，首个命中生效） */
        private List<Rule> rules = new ArrayList<>();

        public String getDefaultDomain() {
            return defaultDomain;
        }

        public void setDefaultDomain(String defaultDomain) {
            this.defaultDomain = defaultDomain;
        }

        public List<Rule> getRules() {
            return rules;
        }

        public void setRules(List<Rule> rules) {
            this.rules = rules;
        }

        public static class Rule {
            private String name;
            private List<String> agentPrefixes = new ArrayList<>();
            private List<String> profileIn = new ArrayList<>();
            private String domain;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public List<String> getAgentPrefixes() {
                return agentPrefixes;
            }

            public void setAgentPrefixes(List<String> agentPrefixes) {
                this.agentPrefixes = agentPrefixes;
            }

            public List<String> getProfileIn() {
                return profileIn;
            }

            public void setProfileIn(List<String> profileIn) {
                this.profileIn = profileIn;
            }

            public String getDomain() {
                return domain;
            }

            public void setDomain(String domain) {
                this.domain = domain;
            }
        }
    }

    /**
     * 分类配置。
     *
     * <p>配置前缀 {@code yunxi.intent.classification.*}。mode 三值：
     * rule（默认）| llm（跳过规则直连 LLM）| hybrid（规则优先 LLM 兜底）。</p>
     */
    public static class Classification {
        /** 分类模式：rule|llm|hybrid（默认 rule） */
        private String mode = "rule";

        /** hybrid 模式规则直出阈值（默认 0.6；rule 模式语义一致） */
        private double ruleConfidenceThreshold = 0.6;

        /** LLM 通道配置 */
        private Llm llm = new Llm();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public double getRuleConfidenceThreshold() {
            return ruleConfidenceThreshold;
        }

        public void setRuleConfidenceThreshold(double ruleConfidenceThreshold) {
            this.ruleConfidenceThreshold = ruleConfidenceThreshold;
        }

        public Llm getLlm() {
            return llm;
        }

        public void setLlm(Llm llm) {
            this.llm = llm;
        }

        public static class Llm {
            /** LLM 通道总开关 */
            private boolean enabled = true;

            /** 低成本快模型（qwen-turbo 级别） */
            private String model = "qwen-turbo";

            /** 超时（ms，resilience4j TimeLimiter） */
            private long timeoutMs = 2000;

            /** LLM 输出最低采纳置信度（[0,1]，默认 0.5） */
            private double minConfidence = 0.5;

            /** 白名单超过该条数时用重写后 query 规则粗筛留 Top-N（默认 30） */
            private int maxIntents = 30;

            /** LLM 结果缓存 */
            private Cache cache = new Cache();

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

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

            public double getMinConfidence() {
                return minConfidence;
            }

            public void setMinConfidence(double minConfidence) {
                this.minConfidence = minConfidence;
            }

            public int getMaxIntents() {
                return maxIntents;
            }

            public void setMaxIntents(int maxIntents) {
                this.maxIntents = maxIntents;
            }

            public Cache getCache() {
                return cache;
            }

            public void setCache(Cache cache) {
                this.cache = cache;
            }
        }

        /** LLM 结果缓存配置 */
        public static class Cache {
            /** TTL 天数（默认 7） */
            private int ttlDays = 7;

            /** 最大条数（超出抽样清理，默认 10000） */
            private int maxSize = 10000;

            public int getTtlDays() {
                return ttlDays;
            }

            public void setTtlDays(int ttlDays) {
                this.ttlDays = ttlDays;
            }

            public int getMaxSize() {
                return maxSize;
            }

            public void setMaxSize(int maxSize) {
                this.maxSize = maxSize;
            }
        }
    }

    /**
     * 热更新配置。
     *
     * <p>配置前缀 {@code yunxi.intent.reload.*}。poll-seconds 默认 -1（关闭文件轮询），
     * 仅 actuator 端点显式触发。</p>
     */
    public static class Reload {
        /** 热更新总开关（默认 true，端点/轮询生效前置条件） */
        private boolean enabled = true;

        /** 文件轮询间隔秒（>0 启用 file: 前缀资源轮询；默认 -1 关闭） */
        private long pollSeconds = -1;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollSeconds() {
            return pollSeconds;
        }

        public void setPollSeconds(long pollSeconds) {
            this.pollSeconds = pollSeconds;
        }
    }
}
