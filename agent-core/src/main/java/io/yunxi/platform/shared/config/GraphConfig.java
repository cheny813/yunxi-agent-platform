package io.yunxi.platform.shared.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排图：节点与边的集合，对应 aistio 的 DefinitionSpec。
 */
@Data
public class GraphConfig {

    private List<GraphNodeConfig> nodes = new ArrayList<>();

    private List<GraphEdgeConfig> edges = new ArrayList<>();
}
