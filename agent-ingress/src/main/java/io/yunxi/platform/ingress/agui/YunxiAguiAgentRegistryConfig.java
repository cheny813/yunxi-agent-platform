package io.yunxi.platform.ingress.agui;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.shared.dto.AgentInfoDto;

/**
 * AG-UI agent 注册表接线（产品平面适配层）。
 *
 * <p>框架 AguiMvcAutoConfiguration 仅在容器中存在 AguiAgentRegistry bean 时挂 AG-UI 端点，
 * 故此处显式提供该 bean，使框架内置 AguiMvcController / AguiRestController 生效，
 * 取代 yunxi 自写的 AguiController（命名主权铁律一）。</p>
 *
 * <p>注册内容：遍历平台已知 agent 清单，对每个 agentId（不含 -agent 后缀，与既有 AG-UI 路由一致）
 * 注册 factory，委托 AgentService.getAgentInstance 获取 Agent 实例。
 * 框架内置的 Agent bean 自动扫描（AguiAgentRegistryAutoConfiguration）已在平台主类 exclude，
 * 避免与 AgentService 权威目录产生第二套注册路径；本配置是 AG-UI agent 发现的唯一注册源，
 * 注册命名以 AG-UI 路由用的 {name} 为准，{name}-agent 后缀差异只在 AgentService 内部解析时由 getAgentInstance 吸收。</p>
 */
@Configuration
public class YunxiAguiAgentRegistryConfig {

    @Bean
    public AguiAgentRegistry aguiAgentRegistry(AgentService agentService) {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        for (AgentInfoDto dto : agentService.listAgents()) {
            if (dto.isHidden()) {
                continue;
            }
            String agentId = dto.getName();
            registry.registerFactory(agentId, () -> agentService.getAgentInstance(agentId));
        }
        return registry;
    }
}
