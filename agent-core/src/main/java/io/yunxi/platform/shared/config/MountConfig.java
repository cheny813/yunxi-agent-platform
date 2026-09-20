package io.yunxi.platform.shared.config;

import lombok.Data;

/**
 * 编排挂载定义：一个可被触发的入口。
 * <p>绑定 YAML 路径 {@code agents.<key>.orchestration.mounts[]}。</p>
 */
@Data
public class MountConfig {

    /** 挂载名称，必填且同 agent 内唯一 */
    private String name;

    /** 挂载分组（用于控制台归类展示） */
    private String group;

    /** 挂载描述 */
    private String description;

    /** 挂载体：声明 kind=agent / workflow 及触发方式 */
    private MountBodyConfig body;

    /** 是否透出子 Agent 事件，缺省继承 orchestration.forwardEvents */
    private Boolean forwardEvents;
}
