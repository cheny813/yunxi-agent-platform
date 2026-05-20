package io.yunxi.platform.business.a2apipeline.service;

import io.yunxi.platform.business.a2apipeline.model.PipelineContext;
import io.yunxi.platform.shared.dto.StageResult;

import java.util.Map;

/**
 * 流水线阶段接口
 * <p>
 * 每个阶段必须实现门控机制：
 * <ul>
 *   <li>canExecute: 前置条件检查，决定阶段是否可执行</li>
 *   <li>execute: 执行阶段逻辑</li>
 *   <li>validateResult: 后置条件验证，确认执行结果有效</li>
 * </ul>
 * 未实现的阶段应返回 canExecute=false 并说明原因，而非静默伪造成功。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface PipelineStage {

    /**
     * 阶段名称（唯一标识）
     */
    String getName();

    /**
     * 前置条件门控：是否可以执行此阶段
     *
     * @param previousResults 前序阶段的执行结果
     * @return true 表示可执行，false 表示跳过
     */
    boolean canExecute(Map<String, StageResult> previousResults);

    /**
     * 执行阶段逻辑
     *
     * @param context         流水线上下文
     * @param previousResults 前序阶段的执行结果
     * @return 阶段执行结果
     */
    StageResult execute(PipelineContext context, Map<String, StageResult> previousResults);

    /**
     * 后置条件验证：确认执行结果是否有效
     *
     * @param result 阶段执行结果
     * @return true 表示结果有效，false 表示结果无效
     */
    boolean validateResult(StageResult result);
}
