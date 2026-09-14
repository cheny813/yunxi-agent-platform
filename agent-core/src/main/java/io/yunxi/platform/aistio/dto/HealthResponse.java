package io.yunxi.platform.aistio.dto;

import lombok.Data;

/**
 * 健康检查响应（{@code GET /agentscope/health}）。
 *
 * <p>aistio 默认健康检查探测路径为 {@code /agentscope}，故返回固定 {@code {"status":"ok"}}。</p>
 */
@Data
public class HealthResponse {

    private String status = "ok";
}
