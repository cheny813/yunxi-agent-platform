package io.yunxi.platform.intent.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 意图引擎配置（前缀 yunxi.intent，业务层惯例，对齐 yunxi.muse.*）。
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
@ConfigurationProperties(prefix = "yunxi.intent")
public class IntentProperties {

    /** 总开关（false = 仅场景模式，行为等价旧 SceneDetectionService） */
    private boolean enabled = true;

    /** NER 词典文件（支持 classpath:/file:/url: 前缀，默认指向框架最小演示集） */
    private String nerDictionary = "classpath:intent/ner-dictionaries.yml";

    /** 改写启用 */
    private boolean rewriteEnabled = true;

    /** 改写处理器名列表（按序执行；不存在的处理器名 warn 跳过） */
    private List<String> rewriteProcessors = List.of("terminology");

    /** 术语表文件（支持 classpath:/file:/url: 前缀） */
    private String terminologyTable = "classpath:intent/terminology.yml";

    /** 意图树文件（支持 classpath:/file:/url: 前缀） */
    private String intentTree = "classpath:intent/intent-tree.yml";

    /** 映射表文件（支持 classpath:/file:/url: 前缀） */
    private String mappingTable = "classpath:intent/intent-mapping.yml";

    /** 意图路由配置（M2.1：识别→路由闭环，默认关闭渐进式上线） */
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

    /**
     * 意图路由配置（M2.1）。
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
}
