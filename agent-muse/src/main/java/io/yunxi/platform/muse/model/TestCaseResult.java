package io.yunxi.platform.muse.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 单个测试用例的执行结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseResult {
    /** 用例名（从测试输出解析，或文件名） */
    private String name;
    /** 是否通过 */
    private boolean passed;
    /** 标准输出（截断） */
    private String stdout;
    /** 标准错误 / 失败信息 */
    private String stderr;
    /** 执行耗时（毫秒） */
    private long durationMs;
    /** 错误信息（异常/断言失败摘要） */
    private String error;
}
