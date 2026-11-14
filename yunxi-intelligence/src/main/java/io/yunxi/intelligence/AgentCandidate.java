package io.yunxi.intelligence;

/**
 * 智能平面。参与意图分类的候选 Agent 卡片。卡片是短文本（名称 + 派生简介），不是系统提示词全文，
 * 既控制 token 成本也缩小提示注入面。
 *
 * @param agentId Agent 唯一 id（分类输出的闭集约束）
 * @param name    Agent 展示名
 * @param card    派生的分类卡片（名称 + 首句简介）
 */
public record AgentCandidate(String agentId, String name, String card) {
}
