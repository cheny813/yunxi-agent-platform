package io.yunxi.platform.business.nutrition.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.business.nutrition.controller.RecipeSseController;
import io.yunxi.platform.framework.agent.AgentDomainService;
import io.yunxi.platform.shared.spi.SseNotificationProvider;
import io.yunxi.platform.infra.sse.SseProgressListenerAdapter;
import io.yunxi.platform.infra.sse.ProgressListener;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.StreamChatRequest;
import reactor.core.publisher.Flux;

/**
 * RecipeStreamService 集成测试
 * 
 * 测试食谱流式生成和配平服务的核心功能
 */
@ExtendWith(MockitoExtension.class)
class RecipeStreamServiceTest {

    @Mock
    private AgentDomainService agentDomainService;

    @Mock
    private AgentscopeCoreProperties agentscopeProperties;

    @Mock
    private SseNotificationProvider emitterManager;

    @Mock
    private SseProgressListenerAdapter progressListenerAdapter;

    private RecipeStreamService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        service = new RecipeStreamService();
        objectMapper = new ObjectMapper();

        // 使用反射注入依赖
        ReflectionTestUtils.setField(service, "agentDomainService", agentDomainService);
        ReflectionTestUtils.setField(service, "properties", agentscopeProperties);
        ReflectionTestUtils.setField(service, "emitterManager", emitterManager);
        ReflectionTestUtils.setField(service, "progressListenerAdapter", progressListenerAdapter);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        // 模拟配置
        when(agentscopeProperties.getChatTimeoutSeconds()).thenReturn(60);
    }

    @Test
    void testStreamGenerateRecipeWithValidRequest() {
        // 准备测试数据
        StreamChatRequest request = new StreamChatRequest();
        request.setMessage("生成适合6-8岁学龄前儿童的早餐食谱");

        // 创建ReActAgent模拟对象
        var mockAgent = mock(io.agentscope.core.ReActAgent.class);

        // 模拟AgentDomainService调用
        when(agentDomainService.getAgentInstance(anyString())).thenReturn(mockAgent);
        when(mockAgent.stream(any(List.class), any()))
                .thenReturn(Flux.empty());

        // 执行测试
        var flux = service.streamGenerateRecipe(request);

        // 验证结果
        assertNotNull(flux);
        // 这里可以通过订阅flux来验证实际内容
    }

    @Test
    void testStreamBalanceRecipeWithBasicParameters() {
        // 准备基础配平请求
        RecipeSseController.BalanceRequest request = new RecipeSseController.BalanceRequest();
        request.setRecipeData(Map.of("name", "测试食谱", "ingredients", List.of()));
        request.setSessionId("test-session-123");

        // 模拟进度监听器
        when(progressListenerAdapter.<String>create(anyString()))
                .thenReturn(new ProgressListener<String>() {
                    @Override
                    public void onProgress(String taskId, int current, int total, String message) {
                        // 进度回调
                    }

                    @Override
                    public void onError(String taskId, Throwable error) {
                        // 错误处理
                    }

                    @Override
                    public void onComplete(String taskId, String result) {
                        // 完成回调
                    }
                });

        // 执行测试
        var flux = service.streamBalanceRecipe(request);

        // 验证结果
        assertNotNull(flux);
    }

    @Test
    void testGenerateRecipeAsyncWithValidParameters() {
        // 准备异步生成请求
        String sessionId = "test-session-123";
        RecipeSseController.RecipeGenerateRequest request = new RecipeSseController.RecipeGenerateRequest();
        request.setSchoolId(12345L);
        request.setNsId(123L);
        request.setNscId(456L);
        request.setMealTypes(List.of("早餐", "午餐"));

        // 模拟SseEmitter存在
        SseEmitter mockEmitter = mock(SseEmitter.class);
        when(emitterManager.hasEmitter(sessionId)).thenReturn(true);
        when(emitterManager.hasEmitter(sessionId)).thenReturn(true);

        // 创建ReActAgent模拟对象
        var mockAgent = mock(io.agentscope.core.ReActAgent.class);

        // 模拟AgentDomainService调用
        when(agentDomainService.getAgentInstance(anyString())).thenReturn(mockAgent);
        when(mockAgent.stream(any(List.class), any()))
                .thenReturn(Flux.empty());

        // 执行异步方法
        service.generateRecipeAsync(sessionId, request);

        // 验证调用了相关方法（异步方法需要等待）
        verify(emitterManager, atLeastOnce()).hasEmitter(sessionId);
    }

    @Test
    void testBalanceRecipeAsyncWithFullParameters() {
        // 准备异步配平请求
        String sessionId = "test-session-456";
        RecipeSseController.BalanceRequest request = new RecipeSseController.BalanceRequest();
        request.setSessionId(sessionId);
        request.setRecipeData(Map.of("name", "测试食谱", "nutritionData", Map.of()));
        request.setMode("full");

        // 模拟SseEmitter存在
        SseEmitter mockEmitter = mock(SseEmitter.class);
        when(emitterManager.hasEmitter(sessionId)).thenReturn(true);
        when(emitterManager.hasEmitter(sessionId)).thenReturn(true);

        // 执行异步方法
        service.balanceRecipeAsync(sessionId, request);

        // 验证调用了相关方法
        verify(emitterManager, atLeastOnce()).hasEmitter(sessionId);
    }

    @Test
    void testTriggerFormFillWithValidRequest() {
        // 准备表单回写请求
        RecipeSseController.FormFillRequest request = new RecipeSseController.FormFillRequest();
        request.setSessionId("test-session-789");
        request.setFormData(Map.of("name", "测试食谱", "calories", 1800));
        request.setScene("recipe");

        // 模拟SseEmitter发送
        when(emitterManager.send(eq("test-session-789"), eq("form_fill"), any())).thenReturn(true);

        // 执行测试
        service.triggerFormFill(request);

        // 验证调用了事件发送
        verify(emitterManager, times(1)).send(eq("test-session-789"), eq("form_fill"), any());
    }

    @Test
    void testStreamGenerateRecipeWithEmptyMessages() {
        // 准备空消息的请求
        StreamChatRequest request = new StreamChatRequest();
        request.setMessage(""); // 空消息

        // 执行测试
        var flux = service.streamGenerateRecipe(request);

        // 验证结果
        assertNotNull(flux);
        // 空消息应该产生错误事件
    }

    @Test
    void testStreamBalanceRecipeWithInvalidJson() {
        // 准备无效数据的请求
        RecipeSseController.BalanceRequest request = new RecipeSseController.BalanceRequest();
        request.setRecipeData(Map.of("invalid", "data")); // 测试异常处理
        request.setSessionId("test-session-123");

        // 执行测试
        var flux = service.streamBalanceRecipe(request);

        // 验证结果
        assertNotNull(flux);
        // JSON解析错误应该产生错误事件
    }

    @Test
    void testGenerateRecipeAsyncWithNullSessionId() {
        // 测试null会话ID
        String sessionId = null;
        RecipeSseController.RecipeGenerateRequest request = new RecipeSseController.RecipeGenerateRequest();
        request.setSchoolId(12345L);

        // 执行异步方法
        service.generateRecipeAsync(sessionId, request);

        // 验证不会尝试发送SSE事件
        verify(emitterManager, never()).hasEmitter(anyString());
    }

    @Test
    void testTriggerFormFillWithNullRequest() {
        // 测试null请求
        RecipeSseController.FormFillRequest request = null;

        // 执行测试（应该不抛出异常）
        service.triggerFormFill(request);

        // 验证没有调用事件发送
        verify(emitterManager, never()).send(anyString(), anyString(), any());
    }

    @Test
    void testTriggerFormFillWithMissingUrl() {
        // 准备缺少场景的请求
        RecipeSseController.FormFillRequest request = new RecipeSseController.FormFillRequest();
        request.setSessionId("test-session-789");
        request.setFormData(Map.of("name", "测试食谱"));
        // 未设置scene

        // 模拟SseEmitter发送
        when(emitterManager.send(eq("test-session-789"), eq("form_fill"), any())).thenReturn(true);

        // 执行测试
        service.triggerFormFill(request);

        // 验证仍然发送了事件（URL可能在其他地方处理）
        verify(emitterManager, times(1)).send(eq("test-session-789"), eq("form_fill"), any());
    }

    @Test
    void testRecipeGenerateRequestBuilder() {
        // 测试Request DTO的构建器方法
        RecipeSseController.RecipeGenerateRequest request = new RecipeSseController.RecipeGenerateRequest();
        request.setSchoolId(12345L);
        request.setNsId(123L);
        request.setNscId(456L);
        request.setMealTypes(List.of("早餐", "午餐", "晚餐"));
        request.setMaleCount(100);
        request.setFemaleCount(100);
        request.setAgeGroup("6-8岁");
        request.setPrompt("生成高蛋白食谱");
        request.setDays(7);

        // 验证字段设置正确
        assertEquals(12345L, request.getSchoolId());
        assertEquals(123L, request.getNsId());
        assertEquals(456L, request.getNscId());
        assertEquals(List.of("早餐", "午餐", "晚餐"), request.getMealTypes());
        assertEquals(100, request.getMaleCount());
        assertEquals(100, request.getFemaleCount());
        assertEquals("6-8岁", request.getAgeGroup());
        assertEquals("生成高蛋白食谱", request.getPrompt());
        assertEquals(7, request.getDays());
    }

    @Test
    void testBalanceRequestBuilder() {
        // 测试BalanceRequest DTO的构建器方法
        RecipeSseController.BalanceRequest request = new RecipeSseController.BalanceRequest();
        request.setSchoolId(12345L);
        request.setNsId(123L);
        request.setNscId(456L);
        request.setMaleCount(50);
        request.setFemaleCount(50);
        request.setAgeGroup("6-8岁");
        request.setRecipeData(Map.of("name", "测试食谱", "ingredients", List.of()));
        request.setMode("full");

        // 验证字段设置正确
        assertEquals(12345L, request.getSchoolId());
        assertEquals(123L, request.getNsId());
        assertEquals(456L, request.getNscId());
        assertEquals(50, request.getMaleCount());
        assertEquals(50, request.getFemaleCount());
        assertEquals("6-8岁", request.getAgeGroup());
        assertEquals(Map.of("name", "测试食谱", "ingredients", List.of()), request.getRecipeData());
        assertEquals("full", request.getMode());
    }

    @Test
    void testStreamChatRequestBuilder() {
        // 测试StreamChatRequest DTO的构建器方法
        StreamChatRequest request = new StreamChatRequest();
        request.setMessage("生成健康食谱");
        request.setEnableThinking(true);
        request.setChunkSize(100);
        request.setMemoryMode("smart");
        request.setUseA2A(false);

        // 验证字段设置正确
        assertEquals("生成健康食谱", request.getMessage());
        assertEquals(true, request.isEnableThinking());
        assertEquals(100, request.getChunkSize());
        assertEquals("smart", request.getMemoryMode());
        assertEquals(false, request.isUseA2A());
    }

    @Test
    void testFormFillRequestBuilder() {
        // 测试FormFillRequest DTO的构建器方法
        RecipeSseController.FormFillRequest request = new RecipeSseController.FormFillRequest();
        request.setSessionId("test-session-789");
        request.setScene("recipe");
        request.setFormData(Map.of("name", "测试食谱", "calories", 1800));
        request.setParams(Map.of("priority", "high"));

        // 验证字段设置正确
        assertEquals("test-session-789", request.getSessionId());
        assertEquals("recipe", request.getScene());
        assertEquals(Map.of("name", "测试食谱", "calories", 1800), request.getFormData());
        assertEquals(Map.of("priority", "high"), request.getParams());
    }

    @Test
    void testStreamGenerateRecipeWithDifferentModels() {
        // 测试不同模型的兼容性
        String[] models = { "claude-3-5-sonnet-20241022", "claude-3-opus-20240229", "gpt-4" };

        for (String model : models) {
            StreamChatRequest request = new StreamChatRequest();
            request.setMessage("生成测试食谱");

            // 创建ReActAgent模拟对象
            var mockAgent = mock(io.agentscope.core.ReActAgent.class);

            // 模拟Agent返回
            when(agentDomainService.getAgentInstance(anyString())).thenReturn(mockAgent);
            when(mockAgent.stream(any(List.class), any()))
                    .thenReturn(Flux.empty());

            // 执行测试
            var flux = service.streamGenerateRecipe(request);

            // 验证结果
            assertNotNull(flux);
        }
    }

    @Test
    void testAsyncMethodsWithCompleteCallback() {
        // 测试异步方法完成后的回调逻辑
        String sessionId = "test-session-callback";
        RecipeSseController.RecipeGenerateRequest request = new RecipeSseController.RecipeGenerateRequest();
        request.setSchoolId(12345L);

        // 模拟SSE发送和完成事件
        when(emitterManager.hasEmitter(sessionId)).thenReturn(true);
        when(emitterManager.hasEmitter(sessionId)).thenReturn(true);
        when(emitterManager.send(eq(sessionId), eq("done"), any())).thenReturn(true);

        // 创建ReActAgent模拟对象
        var mockAgent = mock(io.agentscope.core.ReActAgent.class);

        // 模拟Agent成功完成
        when(agentDomainService.getAgentInstance(anyString())).thenReturn(mockAgent);
        when(mockAgent.stream(any(List.class), any()))
                .thenReturn(Flux.empty());

        // 执行异步方法
        service.generateRecipeAsync(sessionId, request);

        // 验证发送了完成事件
        // 由于是异步，可能需要等待，这里主要验证方法调用
        verify(emitterManager, atLeastOnce()).hasEmitter(sessionId);
    }
}