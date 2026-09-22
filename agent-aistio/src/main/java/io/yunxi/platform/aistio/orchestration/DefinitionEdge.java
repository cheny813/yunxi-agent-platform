package io.yunxi.platform.aistio.orchestration;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排图边（aistio DefinitionSpec.edges 元素）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DefinitionEdge {

    private String from;
    private String to;
    private List<String> on = new ArrayList<>();
    private String condition;

    public DefinitionEdge() {
    }

    public DefinitionEdge(String from, String to, List<String> on, String condition) {
        this.from = from;
        this.to = to;
        this.on = on != null ? on : new ArrayList<>();
        this.condition = condition;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public List<String> getOn() {
        return on;
    }

    public void setOn(List<String> on) {
        this.on = on;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }
}
