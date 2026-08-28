package io.yunxi.platform.shared.dto;

import lombok.Data;

import java.util.Map;

/**
 * 人机确认（HITL）结果回传项。
 *
 * <p>当 Agent 执行需人工确认的工具（由 {@code extensions.hitl.toolGate} 配置）时，
 * 服务端会推送 {@code REQUIRE_USER_CONFIRM} 事件并挂起 Agent。此时需由调用方
 * 携带本对象再次发起请求，AgentScope 才会恢复执行。
 *
 * <p>恢复机制完全由 AgentScope 提供：本对象会被转换为
 * {@code io.agentscope.core.event.ConfirmResult} 并写入输入消息的
 * {@code Msg.METADATA_CONFIRM_RESULTS} 元数据键，由框架校验后继续执行或拒绝。
 * yunxi 仅负责 API 层到框架层的参数转换，不实现确认逻辑本身。
 *
 * <p><b>字段来源</b>：{@code REQUIRE_USER_CONFIRM} 事件的
 * {@code content.toolCalls} 数组中已包含每个待确认工具调用的 {@code id}、
 * {@code name} 与 {@code input}，调用方原样回传即可。
 *
 * @author yunxi-agent-platform
 */
@Data
public class ConfirmResultRequest {

    /**
     * 待确认的工具调用 ID（必填）。
     *
     * <p>取自 {@code REQUIRE_USER_CONFIRM} 事件中的 {@code toolCalls[].id}。
     * 该 ID 必须对应当前处于 ASKING（待确认）状态的工具调用，
     * 否则 AgentScope 会拒绝并提示引用了过期或无关的调用。
     */
    private String toolCallId;

    /**
     * 是否批准执行（默认 false，即拒绝）。
     *
     * <p>采用"默认拒绝"的安全姿态：调用方必须显式批准才会执行工具。
     */
    private boolean approved = false;

    /**
     * 工具名称（可选，建议回传）。
     *
     * <p>取自 {@code REQUIRE_USER_CONFIRM} 事件中的 {@code toolCalls[].name}，
     * 用于重建框架侧的 {@code ToolUseBlock}。
     */
    private String toolName;

    /**
     * 工具入参（可选，建议回传）。
     *
     * <p>取自 {@code REQUIRE_USER_CONFIRM} 事件中的 {@code toolCalls[].input}。
     * 用户也可在确认时修改入参后再执行（AgentScope 支持该能力）。
     */
    private Map<String, Object> input;
}
