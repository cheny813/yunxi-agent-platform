package io.yunxi.platform.intent.snapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.yunxi.platform.intent.classify.IntentTree;

/**
 * 意图树快照。
 *
 * <p>全量节点 + 三索引（byId / bySceneName / leafFirst）。Node 沿用
 * {@code IntentTree.Node}（public static class），含可选 description / confidence 字段。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record TreeSnapshot(List<IntentTree.Node> allNodes,
                           Map<String, IntentTree.Node> byId,
                           Map<String, IntentTree.Node> bySceneName,
                           List<IntentTree.Node> leafFirst) {

    /** 空快照（降级/未配置时使用） */
    public static TreeSnapshot empty() {
        return new TreeSnapshot(List.of(), Map.of(), Map.of(), List.of());
    }

    /**
     * 从节点列表构建三索引快照（统一两遍扫描逻辑）。
     *
     * <p>leafFirst：有 parent 的在前、无 parent 的在后，均保持 YAML 声明序；
     * byId / bySceneName：后者按声明序覆盖。IntentTree.parse 与
     * DomainSnapshotMerger 合并后共用此工厂，消除重复。</p>
     */
    public static TreeSnapshot build(List<IntentTree.Node> nodes) {
        List<IntentTree.Node> list = nodes == null ? List.of() : nodes;
        List<IntentTree.Node> leafFirst = new ArrayList<>(list.size());
        for (IntentTree.Node n : list) {
            if (n.parent != null) {
                leafFirst.add(n);
            }
        }
        for (IntentTree.Node n : list) {
            if (n.parent == null) {
                leafFirst.add(n);
            }
        }
        Map<String, IntentTree.Node> byId = new HashMap<>();
        Map<String, IntentTree.Node> byScene = new HashMap<>();
        for (IntentTree.Node n : list) {
            if (n.id != null) {
                byId.put(n.id, n);
            }
            if (n.sceneName != null) {
                byScene.put(n.sceneName, n);
            }
        }
        return new TreeSnapshot(List.copyOf(list), Map.copyOf(byId),
                Map.copyOf(byScene), List.copyOf(leafFirst));
    }
}
