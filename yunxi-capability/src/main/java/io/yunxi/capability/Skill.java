package io.yunxi.capability;

/**
 * 能力平面。单个技能的声明式描述。执行语义随框架 Tool 走；此处产品平面只持有注册元数据。
 */
public record Skill(String id, String name, String description, String kind) {
}
