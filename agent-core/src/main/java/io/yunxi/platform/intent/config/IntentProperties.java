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
}
