package io.yunxi.integration;

import io.agentscope.spring.boot.agui.common.AguiProperties;
import io.yunxi.app.RuntimeConfiguration;
import io.yunxi.ingress.ConversationRequest;
import io.yunxi.ingress.ConversationResponse;
import io.yunxi.ingress.IngressGateway;
import io.yunxi.runtime.AgentConfigurer;
import io.yunxi.runtime.ExecutionFacade;
import io.yunxi.runtime.Settle;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 集成测试平面。验证目标架构的默认装配图可以整体拉起：各平面的默认实现经运行时核心装配点正确接线，
 * 且入口织面对「未注册 Agent」的请求能正确结算为 NO_AGENT。
 *
 * <p>独立加载 {@link RuntimeConfiguration} 不会自动绑定 application.yml 的 {@code yunxi.models.catalog}，
 * 故本测试向 Environment 注入最小 catalog（使用不含点号的模型名，避免扁平化拆分嵌套键），
 * 使 {@code ConfigurableModelRoutingPolicy} 的 auto 候选池校验通过。
 */
class ReactorWiringTest {

    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        // 最小模型 catalog：仅声明一个 type=llm 的对话模型，满足 auto 候选池与 defaults 校验。
        Properties props = new Properties();
        props.setProperty("yunxi.models.catalog.test-llm.provider", "dashscope");
        props.setProperty("yunxi.models.catalog.test-llm.model", "test-llm");
        props.setProperty("yunxi.models.catalog.test-llm.type", "llm");
        props.setProperty("yunxi.models.catalog.test-llm.cost-tier", "cheap");
        props.setProperty("yunxi.models.defaults.llm", "test-llm");
        ctx.getEnvironment().getPropertySources().addFirst(new PropertiesPropertySource("testCatalog", props));
        ctx.register(RuntimeConfiguration.class);
        // 裸上下文无框架 AG-UI starter 自动装配，手工补 AguiProperties（默认值即可满足过滤器注册）。
        ctx.registerBean(AguiProperties.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    void reactorWiresUpAndRejectsUnknownAgent() {
        try (AnnotationConfigApplicationContext ctx = context()) {
            assertNotNull(ctx.getBean(AgentConfigurer.class), "运行时装配点应已装配");
            assertNotNull(ctx.getBean(ExecutionFacade.class), "执行门面应已装配");
            assertNotNull(ctx.getBean(IngressGateway.class), "入口织面应已装配");

            IngressGateway gateway = ctx.getBean(IngressGateway.class);
            ConversationRequest request = new ConversationRequest("web", "missing-agent", "user-1", "session-1", "你好");
            ConversationResponse response = gateway.complete(request).block();

            assertNotNull(response, "协议响应不应为空");
            assertEquals(Settle.Status.NO_AGENT, response.getStatus(), "未注册的 Agent 应结算为 NO_AGENT");

            // 四协议聚合的边界契约：未知协议必须在同步路由阶段被拒绝，不进入响应式链，
            // 也不退化到默认适配器（避免把无法识别的请求错误路由到某个 agent）。
            assertThrows(IllegalArgumentException.class,
                    () -> gateway.complete(
                            new ConversationRequest("unknown", "missing-agent", "user-1", "session-1", "你好")).block());
        }
    }
}
