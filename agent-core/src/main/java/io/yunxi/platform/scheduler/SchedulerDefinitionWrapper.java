package io.yunxi.platform.scheduler;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * config/scheduler.yml 的根结构包装类，对应 YAML 顶层 {@code agents: [...]} 列表。
 */
@Data
public class SchedulerDefinitionWrapper {

    private List<ScheduledAgentDefinition> agents = new ArrayList<>();
}
