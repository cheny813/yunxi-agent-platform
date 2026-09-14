package io.yunxi.platform.aistio.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * aistio {@code POST /api/v1/agent-registrations} 的响应体。
 *
 * <p>控制面返回创建出的 agent / binding / instance 以及注册凭证。其中 {@code registrationCredential}
 * 为明文凭证，预留给后续可选的 gRPC ASDP 连接器（Phase 3）做长连接鉴权。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentRegistrationResponse {

    private AgentRef agent;
    private AgentRef binding;
    private InstanceRef instance;
    private String registrationCredential;
    private String credential;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentRef {
        private String id;
        private String agentKey;
        private String tenant;
        private String namespace;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InstanceRef {
        private String id;
        private String agentId;
        private String bindingId;
        private String instanceKey;
        private String routingKey;
    }
}
