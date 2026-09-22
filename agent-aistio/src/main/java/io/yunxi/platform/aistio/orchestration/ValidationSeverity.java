package io.yunxi.platform.aistio.orchestration;

/**
 * 预校验问题的严重级别。
 * <ul>
 *   <li>HARD —— 硬阻断：违反会 fail-fast，不导出 artifact；</li>
 *   <li>WARN —— 软告警：仍导出，结论写入 manifest 供运维参考。</li>
 * </ul>
 */
public enum ValidationSeverity {
    HARD,
    WARN
}
