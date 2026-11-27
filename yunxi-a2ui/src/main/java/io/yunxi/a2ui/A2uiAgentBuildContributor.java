package io.yunxi.a2ui;

import io.a2ui4j.catalog.CatalogPromptBuilder;
import io.a2ui4j.catalog.JsonCatalog;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.definition.AgentSpec;
import io.yunxi.runtime.AgentBuildContributor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A2UI 装配贡献点：per-Agent 声明（spec 的 a2ui 段）与全局开关
 * yunxi.agent.a2ui.enabled 二者 OR 生效——任一开启即向该 Agent 注入
 * A2UI 协议说明中间件，均关闭时不注入（对 Agent 零影响）。
 *
 * <p>判定只在本类一处实现；接线由运行时统一的 AgentBuildContributor 循环完成，
 * 运行时不感知 a2ui 具体实现。</p>
 */
@Component
public class A2uiAgentBuildContributor implements AgentBuildContributor {

    @Value("${yunxi.agent.a2ui.enabled:false}")
    private boolean globalEnabled;

    @Override
    public MiddlewareBase buildTimeMiddleware(AgentSpec spec) {
        boolean perAgent = spec.a2ui() != null && spec.a2ui().enabled();
        if (!globalEnabled && !perAgent) {
            return null;
        }
        return new A2uiPromptMiddleware(basicCatalogPrompt());
    }

    private static String basicCatalogPrompt() {
        try (InputStream in = A2uiAgentBuildContributor.class
                .getResourceAsStream("/spec/v1_0/catalogs/basic/catalog.json")) {
            if (in == null) {
                throw new IllegalStateException("basic catalog 资源缺失");
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return CatalogPromptBuilder.systemPrompt(JsonCatalog.load(new ObjectMapper().readTree(json)), null);
        } catch (IOException e) {
            throw new IllegalStateException("读取 basic catalog 失败", e);
        }
    }
}
