package io.yunxi.platform.framework.observability;

import io.yunxi.platform.framework.agent.AgentDomainService;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

/**
 * Agent 健康指示器
 * <p>
 * 注册到 Spring Boot Actuator 的 /actuator/health 端点，
 * 暴露 Agent 运行时状态信息。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AgentHealthIndicator extends AbstractHealthIndicator {

    private final AgentDomainService agentDomainService;

    public AgentHealthIndicator(AgentDomainService agentDomainService) {
        this.agentDomainService = agentDomainService;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        int agentCount = agentDomainService.countAgents();
        builder.up()
                .withDetail("agents", agentCount)
                .withDetail("status", agentCount > 0 ? "agents_ready" : "no_agents_configured");
    }
}