package io.yunxi.platform.integration.mock;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 模拟食谱生成API服务
 * 用于端到端测试中模拟外部API调用
 */
@Service
public class MockRecipeApiService {

    public Mono<ResponseEntity<Map<String, Object>>> generateRecipe(Map<String, Object> request) {
        // 模拟食谱生成API响应
        Map<String, Object> mockResponse = Map.of(
            "success", true,
            "recipeId", "mock-recipe-12345",
            "status", "generated",
            "balanceScore", 85.5,
            "nutrients", Map.of(
                "protein", 45.2,
                "carbs", 120.1,
                "fat", 32.8,
                "calories", 820.5
            ),
            "dishes", Map.of(
                "breakfast", "小米粥+鸡蛋+牛奶",
                "lunch", "米饭+鸡肉+蔬菜+水果"
            ),
            "timestamp", System.currentTimeMillis()
        );
        
        return Mono.just(ResponseEntity.ok(mockResponse));
    }
    
    public Mono<ResponseEntity<Map<String, Object>>> balanceRecipe(String recipeId) {
        // 模拟食谱配平API响应
        Map<String, Object> mockResponse = Map.of(
            "success", true,
            "recipeId", recipeId,
            "status", "balanced",
            "balanceScore", 92.3,
            "adjustments", Map.of(
                "protein", "+5.2g",
                "carbs", "-10.1g",
                "fat", "+2.8g"
            ),
            "timestamp", System.currentTimeMillis()
        );
        
        return Mono.just(ResponseEntity.ok(mockResponse));
    }
    
    public Mono<ResponseEntity<Map<String, Object>>> analyzeNutrition(Map<String, Object> recipeData) {
        // 模拟营养分析API响应
        Map<String, Object> mockResponse = Map.of(
            "success", true,
            "analysis", Map.of(
                "proteinPercentage", 18.5,
                "carbsPercentage", 55.2,
                "fatPercentage", 26.3,
                "dailyValueCoverage", 85.7,
                "grade", "A"
            ),
            "recommendations", Map.of(
                "addProtein", "建议增加豆制品",
                "reduceFat", "建议减少油炸食物"
            ),
            "timestamp", System.currentTimeMillis()
        );
        
        return Mono.just(ResponseEntity.ok(mockResponse));
    }
}