package io.yunxi.platform.intent.classify;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.yunxi.platform.intent.config.IntentProperties;

/**
 * 意图树（M1 规则通道）。
 *
 * <p>通过 {@link ResourceLoader} 从 YAML 加载意图节点（支持 {@code classpath:} /
 * {@code file:} 前缀，业务方可将意图树外部化到部署目录），构建三个索引：
 * leafFirst（两遍扫描：有 parent 的在前、无 parent 的在后，均按 YAML 声明序）、
 * bySceneName、byId。任何解析异常降级为空树（K9）。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class IntentTree {

    private static final Logger log = LoggerFactory.getLogger(IntentTree.class);

    private final IntentProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private List<Node> leafFirst = List.of();
    private Map<String, Node> bySceneName = Map.of();
    private Map<String, Node> byId = Map.of();

    public IntentTree(IntentProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() {
        load();
    }

    /**
     * 加载意图树（K9 降级为空树，不抛出）。
     */
    void load() {
        try {
            Resource resource = resourceLoader.getResource(properties.getIntentTree());
            if (!resource.exists()) {
                log.warn("[INTENT] 意图树资源不存在，使用空树: {}（可按需配置 yunxi.intent.intent-tree 指向外部文件）", properties.getIntentTree());
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                TreeDoc doc = yamlMapper.readValue(in, TreeDoc.class);
                if (doc == null) {
                    log.error("[INTENT] 意图树加载失败: 文档为空");
                    return;
                }
                List<Node> nodes = doc.intents == null ? List.of() : doc.intents;

                // 两遍扫描：有 parent 的在前（YAML 声明序），无 parent 的在后
                List<Node> leafFirstNodes = new ArrayList<>();
                for (Node n : nodes) {
                    if (n.parent != null) {
                        leafFirstNodes.add(n);
                    }
                }
                for (Node n : nodes) {
                    if (n.parent == null) {
                        leafFirstNodes.add(n);
                    }
                }

                Map<String, Node> byScene = new HashMap<>();
                Map<String, Node> byIdMap = new HashMap<>();
                for (Node n : nodes) {
                    if (n.sceneName != null) {
                        byScene.put(n.sceneName, n);
                    }
                    if (n.id != null) {
                        byIdMap.put(n.id, n);
                    }
                }

                this.leafFirst = List.copyOf(leafFirstNodes);
                this.bySceneName = Map.copyOf(byScene);
                this.byId = Map.copyOf(byIdMap);
                log.info("[INTENT] 意图树加载成功: {} 节点, {} 场景关联", nodes.size(), byScene.size());
            }
        } catch (Exception e) {
            log.error("[INTENT] 意图树加载失败，降级为空树: {}", e.getMessage());
            this.leafFirst = List.of();
            this.bySceneName = Map.of();
            this.byId = Map.of();
        }
    }

    /** 按 sceneName 查意图节点（无关联返回 null） */
    public Node findBySceneName(String sceneName) {
        return sceneName == null ? null : bySceneName.get(sceneName);
    }

    /** 叶子优先遍历所有节点 */
    public List<Node> nodesLeafFirst() {
        return leafFirst;
    }

    /** 按 id 查节点（不存在返回 null） */
    public Node findById(String id) {
        return id == null ? null : byId.get(id);
    }

    /** 意图树文档（YAML 绑定 DTO） */
    public static class TreeDoc {
        public List<Node> intents;
    }

    /** 意图节点 */
    public static class Node {
        public String id;
        public String label;
        public String parent;       // 可 null
        public String sceneName;    // 可 null——三级链场景关联
        public Match match;         // 可 null（纯分组节点）
    }

    /** 匹配配置 */
    public static class Match {
        public List<String> anyKeywords;       // 可 null/空
        public List<List<String>> allKeywords; // 分组 AND，可 null/空
        public List<EntityCond> entities;      // 可 null/空
    }

    /**
     * 实体条件（M1 恒按加分处理）。
     *
     * <p>YAML 支持两种写法（框架层容错）：
     * <pre>
     *   entities: [DATE]                    // 简洁字符串：type=DATE, optional=false
     *   entities: [{type: DATE, optional: true}]  // 完整对象
     * </pre>
     * </p>
     */
    public static class EntityCond {
        public String type;
        public boolean optional;

        public EntityCond() {
        }

        /**
         * 从简洁字符串反序列化：{@code "DATE"} → type="DATE", optional=false。
         * 仅当 YAML 值为字符串（如 {@code entities: [DATE]}）时被 Jackson 调用。
         */
        @JsonCreator
        public static EntityCond fromString(String value) {
            EntityCond c = new EntityCond();
            c.type = value;
            c.optional = false;
            return c;
        }
    }
}
