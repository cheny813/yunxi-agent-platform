package io.yunxi.platform.integration.mock;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 模拟表单填写API服务
 * 用于端到端测试中模拟外部表单填写API调用
 */
@Service
public class MockFormApiService {

    public Mono<ResponseEntity<Map<String, Object>>> fillRecipeForm(Map<String, Object> formData) {
        // 模拟食谱表单填写API响应
        Map<String, Object> mockResponse = Map.of(
            "success", true,
            "formId", "mock-form-12345",
            "url", "https://example.com/form/mock-form-12345",
            "status", "completed",
            "filledFields", Map.of(
                "schoolId", formData.get("schoolId"),
                "ageGroup", formData.get("ageGroup"),
                "mealTypes", formData.get("mealTypes"),
                "studentCount", Map.of(
                    "male", formData.get("maleCount"),
                    "female", formData.get("femaleCount")
                )
            ),
            "timestamp", System.currentTimeMillis()
        );
        
        return Mono.just(ResponseEntity.ok(mockResponse));
    }
    
    public Mono<ResponseEntity<Map<String, Object>>> fillBalanceForm(Map<String, Object> balanceData) {
        // 模拟营养配平表单填写API响应
        Map<String, Object> mockResponse = Map.of(
            "success", true,
            "formId", "mock-balance-form-12345",
            "url", "https://example.com/balance/mock-balance-form-12345",
            "status", "completed",
            "nutrientTargets", Map.of(
                "protein", balanceData.getOrDefault("targetProtein", 45.0),
                "calories", balanceData.getOrDefault("targetCalories", 800.0),
                "fat", balanceData.getOrDefault("targetFat", 30.0)
            ),
            "timestamp", System.currentTimeMillis()
        );
        
        return Mono.just(ResponseEntity.ok(mockResponse));
    }
    
    public Mono<ResponseEntity<Map<String, Object>>> getFormStatus(String formId) {
        // 模拟表单状态查询API响应
        Map<String, Object> mockResponse = Map.of(
            "success", true,
            "formId", formId,
            "status", "completed",
            "lastUpdated", System.currentTimeMillis() - 30000,
            "progress", 100,
            "result", Map.of(
                "submitted", true,
                "approved", true
            )
        );
        
        return Mono.just(ResponseEntity.ok(mockResponse));
    }
    
    public Mono<ResponseEntity<Map<String, Object>>> validateFormData(Map<String, Object> formData) {
        // 模拟表单数据验证API响应
        boolean isValid = formData.containsKey("schoolId") && 
                         formData.containsKey("ageGroup") &&
                         formData.containsKey("mealTypes");
        
        Map<String, Object> mockResponse = Map.of(
            "success", isValid,
            "valid", isValid,
            "errors", isValid ? Map.of() : Map.of(
                "missing_fields", "必填字段缺失: schoolId, ageGroup, mealTypes"
            ),
            "timestamp", System.currentTimeMillis()
        );
        
        return Mono.just(ResponseEntity.ok(mockResponse));
    }
}