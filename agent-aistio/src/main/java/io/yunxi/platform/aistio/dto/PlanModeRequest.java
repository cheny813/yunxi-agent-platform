package io.yunxi.platform.aistio.dto;

import lombok.Data;

/**
 * plan-mode 切换请求体（{@code POST /agentscope/sessions/{id}/plan-mode}）。
 */
@Data
public class PlanModeRequest {

    /** 是否开启 plan-mode，默认 true */
    private boolean enabled = true;
}
