package io.yunxi.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 集成验证 R4 per-tenant 列示端点：
 *  - 未认证 → 401（复用既有 SecurityConfig 全局拦截，证明 /v1/admin/** 受保护）；
 *  - 已认证 → 200（新 @RestController 已接入完整 Spring 上下文、请求映射与 JSON 序列化无误）。
 * 分配/目录内容语义由 {@link TenantAgentAllocationAdminControllerTest} 确定性覆盖（受控 ConfigCenter）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret"
})
class TenantAgentAllocationAdminControllerE2eTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminListingRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/v1/admin/tenants/t1/agents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminListingIsReachableWhenAuthenticated() throws Exception {
        mockMvc.perform(get("/v1/admin/tenants/t1/agents")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("admin", "test-secret")))
                .andExpect(status().isOk());
    }
}
