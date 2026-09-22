package io.yunxi.platform.aistio.orchestration;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * aistio 编排定义规范（DefinitionSpec）：节点与边的集合，对应导出的 definition.json。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DefinitionSpec {

    private List<DefinitionNode> nodes = new ArrayList<>();
    private List<DefinitionEdge> edges = new ArrayList<>();

    public List<DefinitionNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<DefinitionNode> nodes) {
        this.nodes = nodes;
    }

    public List<DefinitionEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<DefinitionEdge> edges) {
        this.edges = edges;
    }
}
