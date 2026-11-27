package io.yunxi.ingress;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.yunxi.runtime.ProtocolUserIdResolver;
import io.yunxi.runtime.Settle;
import io.yunxi.runtime.tenant.HeaderTenantResolver;
import io.yunxi.runtime.tenant.TenantConfig;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;

/**
 * 入口层 transport 验证：{@link ConversationController} 是纯 HTTP 传输层，把结算状态映射为 HTTP 语义状态码，
 * 并对缺省协议归一化为 web。用 standalone MockMvc（new controller + mock 网关）验证，不依赖 Spring Boot 主类，
 * 守护传输语义不漂移（不启真实大模型）。
 */
class ConversationControllerTransportTest {

    private IngressGateway ingressGateway = Mockito.mock(IngressGateway.class);
    private ConversationController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new ConversationController(ingressGateway, new ProtocolUserIdResolver(),
                new HeaderTenantResolver("X-Tenant-Id"), TenantConfig.DISABLED, Mockito.mock(ObjectProvider.class),
                new A2uiStreamProcessor(), new MacroDslStreamProcessor());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private ConversationResponse settleResponse(Settle.Status status) {
        return new ConversationResponse("agent-1", "sess-1", "reply", status,
                status == Settle.Status.OK ? null : status.name());
    }

    private static final String BODY =
            "{\"agentId\":\"agent-1\",\"userId\":\"user-1\",\"sessionId\":\"sess-1\",\"text\":\"hi\"}";

    @Test
    void complete_ok_mapsToHttp200() throws Exception {
        when(ingressGateway.complete(any())).thenReturn(Mono.just(settleResponse(Settle.Status.OK)));
        mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("reply"))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void complete_noAgent_mapsToHttp404() throws Exception {
        when(ingressGateway.complete(any())).thenReturn(Mono.just(settleResponse(Settle.Status.NO_AGENT)));
        mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void complete_busy_mapsToHttp409() throws Exception {
        when(ingressGateway.complete(any())).thenReturn(Mono.just(settleResponse(Settle.Status.BUSY)));
        mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void complete_executionError_mapsToHttp500() throws Exception {
        when(ingressGateway.complete(any())).thenReturn(Mono.just(settleResponse(Settle.Status.EXECUTION_ERROR)));
        mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void complete_withoutProtocol_normalizesToWeb_andStillResolves() throws Exception {
        // 请求未带 protocol 字段时，控制器必须归一化为 web 且不报错（归一化是传输层职责）
        when(ingressGateway.complete(any())).thenReturn(Mono.just(settleResponse(Settle.Status.OK)));
        mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void complete_executionError_propagatesRootCauseDescription() throws Exception {
        // 执行故障的根因描述必须随响应体透传给调用方，避免被吞成通用 EXECUTION_ERROR 而无根因。
        ConversationResponse errorResp = new ConversationResponse(
                "agent-1", "sess-1", null, Settle.Status.EXECUTION_ERROR, "EXECUTION_ERROR",
                "[HTTP 401] InvalidApiKey / API-key is blocked");
        when(ingressGateway.complete(any())).thenReturn(Mono.just(errorResp));
        mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("EXECUTION_ERROR"))
                .andExpect(jsonPath("$.description").value("[HTTP 401] InvalidApiKey / API-key is blocked"));
    }
}
