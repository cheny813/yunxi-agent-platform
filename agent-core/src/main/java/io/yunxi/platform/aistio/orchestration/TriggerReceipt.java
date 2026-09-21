package io.yunxi.platform.aistio.orchestration;

/**
 * 触发回执（webhook 触发侧的返回结构）。
 *
 * <p>只包含 webhook 身份下真实可得的字段：webhook 的 200 响应体是
 * {@code {"status":..., "delivery":{...}}}，其中 {@code delivery.RunID} 是 <b>AutomationRun</b>
 * 的 ID，<b>不是</b> OrchestrationRunID。因此这里没有、也不可能有 {@code runId} 字段。
 *
 * @param deliveryId      AutomationDelivery.ID
 * @param automationRunId AutomationDelivery.RunID（AutomationRun，非编排 run）
 * @param status          queued / dispatched / skipped / replayed / duplicate
 * @param correlationId   贯穿协议键（§3.8），执行侧据此把触发段与执行段关联起来
 */
public record TriggerReceipt(
        String deliveryId,
        String automationRunId,
        String status,
        String correlationId) {
}
