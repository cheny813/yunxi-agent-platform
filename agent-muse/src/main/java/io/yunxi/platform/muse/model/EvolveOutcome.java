package io.yunxi.platform.muse.model;

import lombok.Builder;
import lombok.Data;

/** 一次自进化（评估->迭代）的最终结果。 */
@Data
@Builder
public class EvolveOutcome {
    private String skillName;
    /** 实际迭代次数 */
    private int iterations;
    /** 最近一次评估报告 */
    private EvalReport lastReport;
    /** 最终技能正文（迭代中取得的最高通过率版本） */
    private String finalContent;
    /** 是否全部测试通过 */
    private boolean succeeded;
    /** 上一次评估通过率（用于「无进展提前停止」判断） */
    private double prevPassRate;
}
