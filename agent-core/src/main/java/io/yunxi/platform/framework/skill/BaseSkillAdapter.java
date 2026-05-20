package io.yunxi.platform.framework.skill;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基础技能适配器实现
 *
 * <p>
 * 基于技能定义的适配器，提供通用的技能执行能力
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
public class BaseSkillAdapter implements SkillAdapter {

    /** 技能名称 */
    private final String name;
    
    /** 技能描述 */
    private final String description;

    /** 运行时状态 */
    private volatile boolean available = true;

    /** 执行统计 */
    private final Map<String, Object> executionStats = new ConcurrentHashMap<>();

    /**
     * 构造函数
     */
    public BaseSkillAdapter(String name, String description) {
        this.name = name;
        this.description = description;
        initializeStats();
    }

    /**
     * 初始化统计数据
     */
    private void initializeStats() {
        executionStats.put("totalExecutions", 0);
        executionStats.put("successfulExecutions", 0);
        executionStats.put("failedExecutions", 0);
        executionStats.put("lastExecutionTime", System.currentTimeMillis());
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "prompt", Map.of(
                                "type", "string",
                                "description", "技能执行提示"
                        ),
                        "context", Map.of(
                                "type", "object",
                                "description", "执行上下文信息"
                        )
                ),
                "required", java.util.Arrays.asList("prompt")
        );
    }

    @Override
    public Mono<SkillExecutionResult> execute(Map<String, Object> params) {
        return Mono.fromCallable(() -> {
            log.info("执行技能: {}，参数: {}", getName(), params);
            
            long startTime = System.currentTimeMillis();
            executionStats.put("totalExecutions", 
                    (Integer) executionStats.get("totalExecutions") + 1);
            
            try {
                // 参数验证
                if (!validateParameters(params)) {
                    throw new IllegalArgumentException("参数验证失败");
                }
                
                // 模拟技能执行
                String prompt = (String) params.getOrDefault("prompt", "");
                Object context = params.get("context");
                
                // 执行技能逻辑
                String result = executeSkillLogic(prompt, context);
                
                long executionTime = System.currentTimeMillis() - startTime;
                executionStats.put("successfulExecutions", 
                        (Integer) executionStats.get("successfulExecutions") + 1);
                executionStats.put("lastExecutionTime", System.currentTimeMillis());
                
                log.info("技能执行成功: {}，耗时: {}ms", getName(), executionTime);
                
                return SkillExecutionResult.successWithMetadata(
                        result,
                        Map.of("executionTime", executionTime)
                );
                
            } catch (Exception e) {
                executionStats.put("failedExecutions", 
                        (Integer) executionStats.get("failedExecutions") + 1);
                log.error("技能执行失败: {}", getName(), e);
                
                return SkillExecutionResult.failure("技能执行失败: " + e.getMessage());
            }
        });
    }

    @Override
    public Mono<Boolean> isAvailable() {
        return Mono.just(available)
                .doOnNext(status -> {
                    if (!status) {
                        log.warn("技能不可用: {}", getName());
                    }
                });
    }

    /**
     * 验证参数
     */
    private boolean validateParameters(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        
        Object prompt = params.get("prompt");
        return prompt != null && prompt instanceof String && !((String) prompt).trim().isEmpty();
    }

    /**
     * 执行技能逻辑
     */
    private String executeSkillLogic(String prompt, Object context) {
        // 根据技能类型执行不同的逻辑
        switch (name) {
            case "api-developer":
                return executeApiDeveloperSkill(prompt, context);
            case "code-reviewer":
                return executeCodeReviewerSkill(prompt, context);
            case "database-admin":
                return executeDatabaseAdminSkill(prompt, context);
            default:
                return executeGenericSkill(prompt, context);
        }
    }

    /**
     * 执行API开发者技能
     */
    private String executeApiDeveloperSkill(String prompt, Object context) {
        return "API开发技能执行完成: " + prompt;
    }

    /**
     * 执行代码审查技能
     */
    private String executeCodeReviewerSkill(String prompt, Object context) {
        return "代码审查技能执行完成: " + prompt;
    }

    /**
     * 执行数据库管理技能
     */
    private String executeDatabaseAdminSkill(String prompt, Object context) {
        return "数据库管理技能执行完成: " + prompt;
    }

    /**
     * 执行通用技能
     */
    private String executeGenericSkill(String prompt, Object context) {
        return String.format("技能[%s]执行完成: %s", name, prompt);
    }

    /**
     * 获取执行统计
     */
    public Map<String, Object> getExecutionStats() {
        return new ConcurrentHashMap<>(executionStats);
    }

    /**
     * 设置可用状态
     */
    public void setAvailable(boolean available) {
        this.available = available;
        log.info("设置技能状态: {} -> {}", getName(), available ? "可用" : "不可用");
    }
}