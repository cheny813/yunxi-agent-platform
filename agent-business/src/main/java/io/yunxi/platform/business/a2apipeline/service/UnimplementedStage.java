package io.yunxi.platform.business.a2apipeline.service;

import io.yunxi.platform.business.a2apipeline.model.PipelineContext;
import io.yunxi.platform.shared.dto.StageResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 未实现的流水线阶段
 * <p>
 * canExecute 始终返回 false 并记录警告日志，避免静默伪造成功。
 * 当实际实现可用时，替换此占位类即可。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
public class UnimplementedStage implements PipelineStage {

    /** 阶段名称 */
    private final String name;

    /** 未实现的原因说明 */
    private final String reason;

    /**
     * 构造未实现的流水线阶段
     *
     * @param name   阶段名称
     * @param reason 未实现的原因
     */
    public UnimplementedStage(String name, String reason) {
        this.name = name;
        this.reason = reason;
    }

    /**
     * 获取阶段名称
     *
     * @return 阶段名称
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * 判断阶段是否可执行，始终返回 false 并记录警告日志
     *
     * @param previousResults 前序阶段的执行结果
     * @return 始终返回 false
     */
    @Override
    public boolean canExecute(Map<String, StageResult> previousResults) {
        log.warn("[PipelineStage] 阶段 '{}' 不可执行: {}", name, reason);
        return false;
    }

    /**
     * 执行阶段逻辑，始终抛出 UnsupportedOperationException
     *
     * @param context         流水线上下文
     * @param previousResults 前序阶段的执行结果
     * @return 不返回结果，始终抛出异常
     * @throws UnsupportedOperationException 该阶段未实现
     */
    @Override
    public StageResult execute(PipelineContext context, Map<String, StageResult> previousResults) {
        throw new UnsupportedOperationException(
                String.format("阶段 '%s' 未实现: %s", name, reason));
    }

    /**
     * 验证执行结果，始终返回 false
     *
     * @param result 阶段执行结果
     * @return 始终返回 false
     */
    @Override
    public boolean validateResult(StageResult result) {
        return false;
    }
}
