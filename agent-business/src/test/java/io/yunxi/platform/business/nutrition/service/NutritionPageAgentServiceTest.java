package io.yunxi.platform.business.nutrition.service;

import io.yunxi.platform.framework.pageagent.PageAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NutritionPageAgentService 集成测试
 * 
 * 测试食谱配平相关的 Page Agent 服务的核心功能
 */
@ExtendWith(MockitoExtension.class)
class NutritionPageAgentServiceTest {

    @Mock
    private PageAgentService pageAgentService;

    @Mock
    private RecipeStreamService recipeStreamService;

    private NutritionPageAgentService service;

    @BeforeEach
    void setUp() {
        service = new NutritionPageAgentService();
        // 使用反射注入依赖
        try {
            var pageAgentServiceField = NutritionPageAgentService.class.getDeclaredField("pageAgentService");
            pageAgentServiceField.setAccessible(true);
            pageAgentServiceField.set(service, pageAgentService);

            var recipeStreamServiceField = NutritionPageAgentService.class.getDeclaredField("recipeStreamService");
            recipeStreamServiceField.setAccessible(true);
            recipeStreamServiceField.set(service, recipeStreamService);
        } catch (Exception e) {
            fail("Failed to inject dependencies: " + e.getMessage());
        }
    }

    @Test
    void testGenerateAndFillWithValidRequest() {
        // 准备测试数据
        NutritionPageAgentService.RecipeFillRequest request = new NutritionPageAgentService.RecipeFillRequest();
        request.setSchoolId(12345L);
        request.setNsId(1);
        request.setMaleCount(100);
        request.setFemaleCount(100);
        request.setAgeGroup("6-8岁");
        request.setTargetFormUrl("https://example.com/recipe-form");

        // 模拟 PageAgentService 成功响应
        PageAgentService.PageAgentResult mockPageResult = new PageAgentService.PageAgentResult();
        mockPageResult.setSuccess(true);
        mockPageResult.setError(null);
        when(pageAgentService.execute(any(PageAgentService.PageAgentRequest.class))).thenReturn(mockPageResult);

        // 执行测试
        NutritionPageAgentService.RecipeFillResult result = service.generateAndFill(request);

        // 验证结果
        assertTrue(result.isSuccess());
        assertNotNull(result.getSessionId());
        assertTrue(result.getDuration() >= 0);
        assertNotNull(result.getRecipeData());
        assertNull(result.getError());
        
        // 验证调用了 PageAgentService
        verify(pageAgentService, times(1)).execute(any(PageAgentService.PageAgentRequest.class));
    }

    @Test
    void testGenerateAndFillWithAutoBalance() {
        // 准备带配平的测试数据
        NutritionPageAgentService.RecipeFillRequest request = new NutritionPageAgentService.RecipeFillRequest();
        request.setSchoolId(12345L);
        request.setNsId(1);
        request.setMaleCount(100);
        request.setFemaleCount(100);
        request.setAgeGroup("6-8岁");
        request.setTargetFormUrl("https://example.com/recipe-form");
        request.setBalanceFormUrl("https://example.com/balance-form");
        request.setAutoBalance(true);

        // 模拟 PageAgentService 成功响应
        PageAgentService.PageAgentResult mockPageResult = new PageAgentService.PageAgentResult();
        mockPageResult.setSuccess(true);
        mockPageResult.setError(null);
        when(pageAgentService.execute(any(PageAgentService.PageAgentRequest.class))).thenReturn(mockPageResult);

        // 执行测试
        NutritionPageAgentService.RecipeFillResult result = service.generateAndFill(request);

        // 验证结果
        assertTrue(result.isSuccess());
        assertNull(result.getError());
        
        // 验证调用了两次 PageAgentService（表单填写和配平）
        verify(pageAgentService, times(2)).execute(any(PageAgentService.PageAgentRequest.class));
    }

    @Test
    void testGenerateAndFillWithEmptyUrl() {
        // 准备无效URL的测试数据
        NutritionPageAgentService.RecipeFillRequest request = new NutritionPageAgentService.RecipeFillRequest();
        request.setSchoolId(12345L);
        request.setNsId(1);
        request.setMaleCount(100);
        request.setFemaleCount(100);
        request.setAgeGroup("6-8岁");
        request.setTargetFormUrl(null); // 无效URL

        // 执行测试
        NutritionPageAgentService.RecipeFillResult result = service.generateAndFill(request);

        // 验证失败结果
        assertFalse(result.isSuccess());
        assertEquals("未指定目标表单页面", result.getError());
        
        // 验证没有调用 PageAgentService
        verify(pageAgentService, never()).execute(any(PageAgentService.PageAgentRequest.class));
    }

    @Test
    void testGenerateAndFillWithPageAgentFailure() {
        // 准备测试数据
        NutritionPageAgentService.RecipeFillRequest request = new NutritionPageAgentService.RecipeFillRequest();
        request.setSchoolId(12345L);
        request.setNsId(1);
        request.setMaleCount(100);
        request.setFemaleCount(100);
        request.setAgeGroup("6-8岁");
        request.setTargetFormUrl("https://example.com/recipe-form");

        // 模拟 PageAgentService 失败响应
        PageAgentService.PageAgentResult mockPageResult = new PageAgentService.PageAgentResult();
        mockPageResult.setSuccess(false);
        mockPageResult.setError("页面加载失败");
        when(pageAgentService.execute(any(PageAgentService.PageAgentRequest.class))).thenReturn(mockPageResult);

        // 执行测试
        NutritionPageAgentService.RecipeFillResult result = service.generateAndFill(request);

        // 验证失败结果
        assertFalse(result.isSuccess());
        assertEquals("页面加载失败", result.getError());
        
        // 验证调用了 PageAgentService
        verify(pageAgentService, times(1)).execute(any(PageAgentService.PageAgentRequest.class));
    }

    @Test
    void testFillFormWithValidParameters() {
        // 准备测试数据
        String targetUrl = "https://example.com/recipe-form";
        Map<String, Object> recipeData = Map.of(
                "name", "测试食谱",
                "schoolId", 12345L,
                "ageGroup", "6-8岁"
        );

        // 模拟 PageAgentService 成功响应
        PageAgentService.PageAgentResult mockResult = new PageAgentService.PageAgentResult();
        mockResult.setSuccess(true);
        mockResult.setError(null);
        when(pageAgentService.navigateAndSubmit(eq(targetUrl), eq(recipeData), eq("保存"))).thenReturn(mockResult);

        // 执行测试
        NutritionPageAgentService.RecipeFillResult result = service.fillForm(targetUrl, recipeData);

        // 验证结果
        assertTrue(result.isSuccess());
        assertNotNull(result.getSessionId());
        assertNull(result.getError());
        
        // 验证调用了 navigateAndSubmit
        verify(pageAgentService, times(1)).navigateAndSubmit(eq(targetUrl), eq(recipeData), eq("保存"));
    }

    @Test
    void testFillFormWithNullParameters() {
        // 测试空参数
        NutritionPageAgentService.RecipeFillResult result = service.fillForm(null, null);

        // 验证失败结果
        assertFalse(result.isSuccess());
        assertEquals("参数不完整", result.getError());
        
        // 验证没有调用 navigateAndSubmit
        verify(pageAgentService, never()).navigateAndSubmit(any(), any(), any());
    }

    @Test
    void testRunBalanceWithValidParameters() {
        // 准备测试数据
        String balanceUrl = "https://example.com/balance-form";
        Map<String, Object> recipeData = Map.of(
                "name", "测试食谱",
                "nutritionData", Map.of("calories", 1800, "protein", 45)
        );

        // 模拟 PageAgentService 成功响应
        PageAgentService.PageAgentResult mockResult = new PageAgentService.PageAgentResult();
        mockResult.setSuccess(true);
        mockResult.setError(null);
        when(pageAgentService.execute(any(PageAgentService.PageAgentRequest.class))).thenReturn(mockResult);

        // 执行测试
        NutritionPageAgentService.RecipeFillResult result = service.runBalance(balanceUrl, recipeData);

        // 验证结果
        assertTrue(result.isSuccess());
        assertNotNull(result.getSessionId());
        assertNull(result.getError());
        
        // 验证调用了 execute
        verify(pageAgentService, times(1)).execute(any(PageAgentService.PageAgentRequest.class));
    }

    @Test
    void testRunBalanceWithNullParameters() {
        // 测试空参数
        NutritionPageAgentService.RecipeFillResult result = service.runBalance(null, null);

        // 验证失败结果
        assertFalse(result.isSuccess());
        assertEquals("参数不完整", result.getError());
        
        // 验证没有调用 execute
        verify(pageAgentService, never()).execute(any(PageAgentService.PageAgentRequest.class));
    }

    @Test
    void testRecipeFillRequestBuilders() {
        // 测试 Request DTO 的构建器方法
        NutritionPageAgentService.RecipeFillRequest request = new NutritionPageAgentService.RecipeFillRequest();
        request.setSchoolId(12345L);
        request.setNsId(1);
        request.setMaleCount(100);
        request.setFemaleCount(100);
        request.setAgeGroup("6-8岁");
        request.setTargetFormUrl("https://example.com/form");
        request.setBalanceFormUrl("https://example.com/balance");
        request.setAutoBalance(true);
        request.setCustomPrompt("填写完整营养信息");
        request.setDays(5);

        // 验证字段设置正确
        assertEquals(12345L, request.getSchoolId());
        assertEquals(1, request.getNsId());
        assertEquals(100, request.getMaleCount());
        assertEquals(100, request.getFemaleCount());
        assertEquals("6-8岁", request.getAgeGroup());
        assertEquals("https://example.com/form", request.getTargetFormUrl());
        assertEquals("https://example.com/balance", request.getBalanceFormUrl());
        assertTrue(request.getAutoBalance());
        assertEquals("填写完整营养信息", request.getCustomPrompt());
        assertEquals(5, request.getDays());
    }

    @Test
    void testRecipeFillResultStaticMethods() {
        // 测试成功结果静态方法
        NutritionPageAgentService.RecipeFillResult successResult = NutritionPageAgentService.RecipeFillResult.success();
        assertTrue(successResult.isSuccess());
        assertNull(successResult.getError());

        // 测试失败结果静态方法
        String errorMessage = "页面加载失败";
        NutritionPageAgentService.RecipeFillResult failResult = NutritionPageAgentService.RecipeFillResult.fail(errorMessage);
        assertFalse(failResult.isSuccess());
        assertEquals(errorMessage, failResult.getError());
    }

    @Test
    void testRecipeFillResultFullConstructor() {
        // 测试完整构造方法
        String sessionId = "test-session-123";
        long duration = 5000L;
        Map<String, Object> recipeData = Map.of("test", "data");

        NutritionPageAgentService.RecipeFillResult result = new NutritionPageAgentService.RecipeFillResult(
                true, "test error", recipeData, sessionId, duration
        );

        // 验证所有字段
        assertTrue(result.isSuccess());
        assertEquals("test error", result.getError());
        assertEquals(recipeData, result.getRecipeData());
        assertEquals(sessionId, result.getSessionId());
        assertEquals(duration, result.getDuration());
    }

    @Test
    void testGenerateAndFillWithException() {
        // 准备测试数据
        NutritionPageAgentService.RecipeFillRequest request = new NutritionPageAgentService.RecipeFillRequest();
        request.setSchoolId(12345L);
        request.setNsId(1);
        request.setMaleCount(100);
        request.setFemaleCount(100);
        request.setAgeGroup("6-8岁");
        request.setTargetFormUrl("https://example.com/recipe-form");

        // 模拟 PageAgentService 抛出异常
        when(pageAgentService.execute(any(PageAgentService.PageAgentRequest.class)))
                .thenThrow(new RuntimeException("连接超时"));

        // 执行测试
        NutritionPageAgentService.RecipeFillResult result = service.generateAndFill(request);

        // 验证异常被捕获并转换
        assertFalse(result.isSuccess());
        assertEquals("连接超时", result.getError());
        
        // 验证调用了 PageAgentService
        verify(pageAgentService, times(1)).execute(any(PageAgentService.PageAgentRequest.class));
    }
}