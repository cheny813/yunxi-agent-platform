package io.yunxi.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 端到端验证 P0：四协议 HTTP 入口（ConversationController）已暴露、且受 SecurityConfig 保护。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret"
})
class IngressSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String BODY =
            "{\"agentId\":\"missing\",\"userId\":\"u1\",\"sessionId\":\"s1\",\"text\":\"hi\"}";

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void conversationEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conversationEndpointWithAuthSettlesToNoAgent() throws Exception {
        mockMvc.perform(post("/api/conversations")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("admin", "test-secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                // 未注册 Agent 经 IngressGateway 结算为 NO_AGENT，控制器映射为 404。
                .andExpect(status().isNotFound());
    }
}
