package io.yunxi.intelligence;

import java.util.List;

/**
 * 智能平面。候选 Agent 提供者。候选集是意图分类的闭集约束：分类器只允许从返回列表中选目标。
 *
 * <p>唯一真相源由实现方决定（平台默认实现读 {@code ConfigCenter} 目录并按租户分配过滤），
 * 本 SPI 不引入第二份 Agent 注册表。</p>
 */
public interface AgentCandidateProvider {

    /**
     * 返回候选 Agent 列表（已按租户过滤、按 limit 截断）。
     *
     * @param tenantId 租户 id；null 或空白表示未启用多租户
     * @param limit    候选上限；&le;0 表示不限
     * @return 候选列表（可能为空，表示该租户无可用 Agent）
     */
    List<AgentCandidate> candidates(String tenantId, int limit);
}
