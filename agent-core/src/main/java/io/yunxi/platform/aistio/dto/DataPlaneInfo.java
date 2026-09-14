package io.yunxi.platform.aistio.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * aistio 数据面信息（{@code GET /agentscope/info} 与注册 payload 共用）。
 *
 * <p>字段命名对齐 aistio 官方 byo-stub 注册示例（snake_case），以最大化与 aistiod 的兼容性。
 * 注：aistio 控制面服务端的 JSON 字段 tag 需以实际构建版本为准（联调确认项）。</p>
 */
@Data
public class DataPlaneInfo {

    /** 运行时标识 */
    private String runtime = "yunxi-agent-platform";

    /** 平台版本 */
    private String version = "2.0.3";

    /** 本数据面支持的能力枚举（aistio 契约能力集） */
    private List<String> capabilities;

    /** 契约等级 */
    @JsonProperty("contract_level")
    private int contractLevel = 3;

    /** 会话亲和性 */
    @JsonProperty("session_affinity")
    private String sessionAffinity = "sticky";

    /** 工具 / MCP 服务名集合 */
    private List<String> tools;

    /** 命名空间 */
    private String namespace = "default";

    /** 数据面名称 */
    private String name = "yunxi-agent-platform";

    /** 数据面契约基址（注册时告知控制面「来这里轮询我」；字段名以 aistio 构建为准） */
    private String url;
}
