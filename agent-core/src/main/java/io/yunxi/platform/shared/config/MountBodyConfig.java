package io.yunxi.platform.shared.config;

import lombok.Data;

/**
 * 挂载体：描述挂载的触发目标。
 * <ul>
 *   <li>kind=agent &amp; mode=auto → 触发本 agent（不产出 artifact）；</li>
 *   <li>kind=workflow &amp; mode=auto &amp; 填 definition → 触发已发布的 aistio 定义（key 引用）；</li>
 *   <li>kind=workflow &amp; mode=auto &amp; 填 graph → 内联声明图，由 translator 导出 DefinitionSpec artifact；</li>
 * </ul>
 */
@Data
public class MountBodyConfig {

    /** 挂载类型：agent | workflow */
    private String kind;

    /** 触发模式，当前仅支持 auto */
    private String mode = "auto";

    /** workflow 类型且引用已发布定义时填写 aistio 定义 key（与 graph 二选一） */
    private String definition;

    /** workflow 类型且内联声明时填写编排图（将由 translator 校验并导出 artifact） */
    private GraphConfig graph;
}
