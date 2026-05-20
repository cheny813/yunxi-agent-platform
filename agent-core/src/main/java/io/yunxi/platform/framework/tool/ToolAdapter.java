package io.yunxi.platform.framework.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * 将平台 Tool 接口适配为 AgentScope AgentTool
 *
 * <p>
 * 用于将本地工具（如 RecipeMemoryTool）集成到 AgentScope Agent
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
public class ToolAdapter implements AgentTool {

    /** 平台工具实例 */
    private final Tool tool;
    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper;
    /** 工具熔断器 */
    private final ToolCircuitBreaker circuitBreaker;

    /**
     * 构造工具适配器
     *
     * @param tool            平台工具实例
     * @param circuitBreaker  工具熔断器
     */
    public ToolAdapter(Tool tool, ToolCircuitBreaker circuitBreaker) {
        this.tool = tool;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 构造工具适配器（无熔断器版本）
     *
     * @param tool 平台工具实例
     */
    public ToolAdapter(Tool tool) {
        this.tool = tool;
        this.circuitBreaker = null;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return tool.getName();
    }

    @Override
    public String getDescription() {
        return tool.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        try {
            String schemaJson = tool.getParameterSchema();
            return objectMapper.readValue(schemaJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析工具参数 Schema 失败: {}", tool.getName(), e);
            // 返回基本 schema
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            schema.put("properties", new HashMap<>());
            return schema;
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 熔断检查
        if (circuitBreaker != null && circuitBreaker.isCircuitOpen(tool.getName())) {
            log.warn("工具 [{}] 已熔断，跳过调用", tool.getName());
            return Mono.just(ToolResultBlock.error("工具 " + tool.getName() + " 暂时不可用，请稍后重试"));
        }

        return Mono.fromCallable(() -> {
            try {
                // param.getInput() 返回 Map<String, Object>
                Map<String, Object> params = param.getInput();
                if (params == null) {
                    params = new HashMap<>();
                }

                // 执行工具
                ToolInput input = new ToolInput(params);
                ToolResult result = tool.execute(input);

                // 构建返回结果
                if (result.isSuccess()) {
                    if (circuitBreaker != null) {
                        circuitBreaker.recordSuccess(tool.getName());
                    }
                    String resultJson = objectMapper.writeValueAsString(result.getResult());
                    return ToolResultBlock.text(resultJson);
                } else {
                    if (circuitBreaker != null) {
                        circuitBreaker.recordFailure(tool.getName());
                    }
                    return ToolResultBlock.error(result.getError());
                }
            } catch (Exception e) {
                log.error("工具执行失败: {}", tool.getName(), e);
                if (circuitBreaker != null) {
                    circuitBreaker.recordFailure(tool.getName());
                }
                return ToolResultBlock.error(e.getMessage());
            }
        });
    }
}
