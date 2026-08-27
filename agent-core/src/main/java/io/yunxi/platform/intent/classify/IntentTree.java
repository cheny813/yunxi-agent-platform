package io.yunxi.platform.intent.classify;

import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonCreator;

import io.yunxi.platform.intent.snapshot.TreeSnapshot;
import io.yunxi.platform.intent.util.IntentYaml;

/**
 * 意图树解析器宿主。
 *
 * <p>由 {@link #parse(Resource)} 从 YAML 加载意图节点（支持 {@code classpath:} /
 * {@code file:} 前缀），构建 {@link TreeSnapshot} 三索引：
 * leafFirst（两遍扫描：有 parent 的在前、无 parent 的在后，均按 YAML 声明序）、
 * bySceneName、byId。解析失败降级为空快照（K9）。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class IntentTree {

    private static final Logger log = LoggerFactory.getLogger(IntentTree.class);

    /**
     * 解析意图树资源为不可变快照（K9 降级为空快照）。
     *
     * @param resource YAML 资源
     * @return 意图树快照（永不 null）
     */
    public TreeSnapshot parse(Resource resource) {
        if (resource == null || !resource.exists()) {
            log.warn("[INTENT] 意图树资源不存在，使用空树: {}", resource);
            return TreeSnapshot.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            TreeDoc doc = IntentYaml.mapper().readValue(in, TreeDoc.class);
            if (doc == null) {
                log.error("[INTENT] 意图树加载失败: 文档为空");
                return TreeSnapshot.empty();
            }
            List<Node> nodes = doc.intents == null ? List.of() : doc.intents;

            long sceneCount = nodes.stream().filter(n -> n.sceneName != null).count();
            log.info("[INTENT] 意图树解析成功: {} 节点, {} 场景关联", nodes.size(), sceneCount);
            return TreeSnapshot.build(nodes);
        } catch (Exception e) {
            log.error("[INTENT] 意图树解析失败，降级为空树: {}", e.getMessage());
            return TreeSnapshot.empty();
        }
    }

    /** 意图树文档（YAML 绑定 DTO） */
    public static class TreeDoc {
        public List<Node> intents;
    }

    /**
     * 意图节点。
     *
     * <p>含可选 {@code description}（LLM 白名单语义）与可选
     * {@code confidence}（节点级直出阈值覆盖，缺省用全局 rule-confidence-threshold）。
     * 缺失即 null/空。</p>
     */
    public static class Node {
        public String id;
        public String label;
        public String parent;       // 可 null
        public String sceneName;    // 可 null——三级链场景关联
        public Match match;         // 可 null（纯分组节点）

        /** 节点语义描述（LLM 白名单用，可 null） */
        public String description;

        /** 节点级规则直出阈值覆盖（可 null，缺省用全局配置） */
        public Double confidence;
    }

    /** 匹配配置 */
    public static class Match {
        public List<String> anyKeywords;       // 可 null/空
        public List<List<String>> allKeywords; // 分组 AND，可 null/空
        public List<EntityCond> entities;      // 可 null/空
    }

    /**
     * 实体条件。
     *
     * <p>规则模式恒按加分处理；混合模式启用 optional 语义
     * （optional=false 缺失则节点不匹配，走 LLM 兜底；optional=true 缺失不扣分）。
     * YAML 支持两种写法（框架层容错）：
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
