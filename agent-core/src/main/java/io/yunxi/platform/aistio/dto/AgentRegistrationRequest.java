package io.yunxi.platform.aistio.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Data;

/**
 * aistio 新版（Managed Agents）外部数据面注册请求体。
 *
 * <p>对齐 aistio 控制面 {@code POST /api/v1/agent-registrations}（server.go registerExternalAgent）。
 * 该端点一次调用即创建 agent + binding + instance，并把 {@code routingKey}（本数据面的 /agentscope 契约基址）
 * 写入控制面注册表，使其能通过 HTTP 轮询本数据面（/agentscope/info、/agentscope/sessions 等）。</p>
 *
 * <p>注意：{@code capabilities} / {@code labels} 在 aistio 控制面为 json.RawMessage，这里直接以 List / Map
 * 声明，由 Jackson 序列化为 JSON 数组 / 对象即可，无需再包成字符串。</p>
 */
@Data
@JsonInclude(Include.NON_NULL)
public class AgentRegistrationRequest {

    /** 租户，缺省由控制面填 default */
    private String tenant;

    /** 命名空间，缺省由控制面填 default */
    private String namespace;

    /** 数据面稳定标识（agentKey，必填）；同名 agent 已存在时复用并新建 instance */
    private String agentKey;

    /** 展示名（控制面 UI 显示） */
    private String displayName;

    /** 描述 */
    private String description;

    /** 实例标识（每次启动唯一，必填） */
    private String instanceKey;

    /** 框架名，如 yunxi-agent-platform */
    private String framework;

    /** 框架版本 */
    private String frameworkVersion;

    /** SDK 版本 */
    private String sdkVersion;

    /** 能力清单（对齐 /agentscope/info 的 capabilities） */
    private List<String> capabilities;

    /** 标签 */
    private Map<String, String> labels;

    /** 契约基址：控制面轮询本数据面的 /agentscope/* 端点所用 URL（非空才写入注册表） */
    private String routingKey;

    /** 容量上限，0 表示不限 */
    private int capacity;
}
