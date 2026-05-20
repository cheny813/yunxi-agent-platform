package io.yunxi.platform.shared.exception;

/**
 * Agent 未找到异常 — AgentGateway 调用时抛出
 *
 * @author yunxi-agent-platform
 */
public class AgentNotFoundException extends RuntimeException {

    public AgentNotFoundException(String agentName) {
        super("Agent not found: " + agentName);
    }

    public AgentNotFoundException(String agentName, Throwable cause) {
        super("Agent not found: " + agentName, cause);
    }
}