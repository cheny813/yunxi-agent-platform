package io.yunxi.platform.muse.eval;

import io.yunxi.platform.muse.model.EvalReport;

/**
 * 技能评估器：在沙箱中执行技能附带的测试脚本并产出 {@link EvalReport}。
 *
 * <p>MUSE 自进化闭环第一环。AgentScope 不提供「替你跑业务测试并评分」的能力，
 * 这是 MUSE 相对 AgentScope 的核心新增。</p>
 */
public interface SkillEvaluator {

    /**
     * 评估某技能。
     *
     * @param skillName 技能名
     * @param skillDir  技能目录（含 tests/ 测试脚本，绝对路径）
     * @return 评估报告
     */
    EvalReport evaluate(String skillName, String skillDir);
}
