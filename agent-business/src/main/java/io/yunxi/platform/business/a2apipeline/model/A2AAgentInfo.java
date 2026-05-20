package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A2AAgentInfo A2A Agent信息
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class A2AAgentInfo {

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * Agent类型
     */
    private String agentType;

    /**
     * Agent名称
     */
    private String name;

    /**
     * 端点URL
     */
    private String endpoint;

    /**
     * 能力列表
     */
    private List<String> capabilities;

    /**
     * 创建 Agent 信息
     *
     * @param agentId   Agent ID
     * @param agentType Agent 类型
     * @return Agent 信息实例
     */
    public static A2AAgentInfo create(String agentId, String agentType) {
        return A2AAgentInfo.builder()
                .agentId(agentId)
                .agentType(agentType)
                .build();
    }
}