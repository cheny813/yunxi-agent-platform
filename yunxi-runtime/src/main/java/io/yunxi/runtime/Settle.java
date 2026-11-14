package io.yunxi.runtime;

import java.time.Instant;

/**
 * 运行时平面。一次执行的终态结果。携带稳定的状态机，使入口织面能把每次运行映射到唯一一个用户可见的结果，
 * 而无需重新解释事件。
 */
public record Settle(
        String agentId,
        String userId,
        String sessionId,
        String conversationId,
        Status status,
        String errorCode,
        boolean terminal,
        String description,
        Instant startedAt,
        Instant finishedAt,
        String text) {

    public enum Status {
        OK, BUSY, NO_AGENT, REJECTED, OVERLOAD, EXECUTION_ERROR, STATE_CONFLICT
    }

    /**
     * 成功终态工厂：基于命令的全部标识字段构建一条 {@code OK} 终态，文本即 Agent 最终回复。
     *
     * @param command 原始执行命令（提供 agentId/userId/sessionId/conversationId）
     * @param text    Agent 最终回复文本
     * @return 成功终态
     */
    public static Settle ok(AgentCommand command, String text) {
        Instant now = Instant.now();
        return new Settle(command.agentId(), command.userId(), command.sessionId(), command.conversationId(),
                Status.OK, null, true, "ok", now, now, text);
    }

    /**
     * 失败终态工厂：基于命令标识字段构建一条带状态码与错误码的终态。
     *
     * @param command     原始执行命令
     * @param status      终态状态（如 {@code BUSY}/{@code REJECTED}/{@code OVERLOAD}）
     * @param errorCode   机器可读错误码（同时作为 description 默认值）
     * @param description 人类可读描述
     * @return 失败终态
     */
    public static Settle failure(AgentCommand command, Status status, String errorCode, String description) {
        Instant now = Instant.now();
        return new Settle(command.agentId(), command.userId(), command.sessionId(), command.conversationId(),
                status, errorCode, true, description, now, now, null);
    }

    /**
     * 异常终态工厂：把未受检异常映射为终态。{@link NoSuchAgentException} 映射为
     * {@code NO_AGENT}，其余统一映射为 {@code EXECUTION_ERROR}，错误码取状态名、描述取根因消息。
     * 这是非流式结算路径（{@code settle}）的唯一错误映射入口。
     *
     * @param command 原始执行命令
     * @param error   运行期异常
     * @return 异常终态
     */
    public static Settle fromError(AgentCommand command, Throwable error) {
        ErrorDetail detail = errorDetail(error);
        return failure(command, detail.status(), detail.errorCode(), detail.description());
    }

    /**
     * 异常终态工厂（基于标识符）：语义同 {@link #fromError(AgentCommand, Throwable)}，但不依赖完整
     * {@link AgentCommand}，供不经过命令对象的流式错误边界复用，保证状态映射与根因提取的单一真相源。
     * 会话标识与业务约定一致：{@code conversationId} 取 {@code sessionId}。
     *
     * @param agentId   智能体标识
     * @param userId    用户标识
     * @param sessionId 会话标识
     * @param error     运行期异常
     * @return 异常终态
     */
    public static Settle fromError(String agentId, String userId, String sessionId, Throwable error) {
        ErrorDetail detail = errorDetail(error);
        Instant now = Instant.now();
        return new Settle(agentId, userId, sessionId, sessionId, detail.status(), detail.errorCode(),
                true, detail.description(), now, now, null);
    }

    /**
     * 异常到状态/错误码/根因描述的映射：{@link NoSuchAgentException} 映射为 {@code NO_AGENT}，
     * 其余统一映射为 {@code EXECUTION_ERROR}，描述取根因消息。非流式与流式错误边界共用，避免双份真相。
     *
     * @param error 运行期异常
     * @return 错误明细（状态、错误码、根因描述）
     */
    private static ErrorDetail errorDetail(Throwable error) {
        Status status = error instanceof NoSuchAgentException ? Status.NO_AGENT : Status.EXECUTION_ERROR;
        return new ErrorDetail(status, status.name(), rootCauseMessage(error));
    }

    /**
     * 取异常的根因消息：沿 cause 链追到最里层非自引用的原因，避免被外层包装（如响应式运行时异常、
     * 执行门面包装）的泛化消息覆盖真实根因（如模型服务的鉴权 401、网络超时）。
     * 根因消息为空时回退到顶层消息，保证 {@code description} 永远非空可读。
     *
     * @param error 运行期异常
     * @return 根因可读消息
     */
    private static String rootCauseMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getMessage();
        }
        return message;
    }

    /** 错误明细：状态、错误码、根因描述，作为非流式与流式错误边界共用的映射产物。 */
    private record ErrorDetail(Status status, String errorCode, String description) {
    }
}
