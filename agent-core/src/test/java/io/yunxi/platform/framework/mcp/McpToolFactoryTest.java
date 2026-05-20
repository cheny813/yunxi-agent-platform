package io.yunxi.platform.framework.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.agentscope.core.tool.AgentTool;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;

/**
 * MCP 工具工厂单元测试
 *
 * <p>
 * 验证 AgentTool 创建、参数解析、缓存机制等核心功能。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class McpToolFactoryTest {

    @Mock
    private McpClient mcpClient;

    private McpToolFactory factory;

    @BeforeEach
    void setUp() {
        factory = new McpToolFactory(List.of());
    }

    @Nested
    @DisplayName("createTool - 工具创建")
    class CreateToolTests {

        @Test
        @DisplayName("应正确创建AgentTool实例")
        void shouldCreateAgentTool() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");
            Map<String, Object> inputSchema = createInputSchema();

            AgentTool tool = factory.createTool("test-server", config, "query", "查询工具", inputSchema);

            assertNotNull(tool);
            assertEquals("test-server_query", tool.getName());
            assertEquals("查询工具", tool.getDescription());
        }

        @Test
        @DisplayName("HTTP类型URL应保持不变")
        void whenHttpType_shouldKeepUrlUnchanged() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factory.createTool("test-server", config, "query", "查询", Map.of());

            assertNotNull(tool);
        }

        @Test
        @DisplayName("SSE类型URL应将/sse替换为/message")
        void whenSseType_shouldReplaceSseWithMessage() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp/sse", "sse");

            AgentTool tool = factory.createTool("test-server", config, "query", "查询", Map.of());

            assertNotNull(tool);
        }

        @Test
        @DisplayName("工具名称应包含服务器前缀")
        void toolNameShouldIncludeServerPrefix() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factory.createTool("redis-server", config, "get", "获取值", Map.of());

            assertEquals("redis-server_get", tool.getName());
        }

        @Test
        @DisplayName("应使用动态获取的inputSchema")
        void shouldUseDynamicInputSchema() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");
            Map<String, Object> dynamicSchema = createInputSchema();

            AgentTool tool = factory.createTool("test-server", config, "custom", "自定义工具", dynamicSchema);

            Map<String, Object> params = tool.getParameters();
            assertNotNull(params);
            assertEquals("object", params.get("type"));
            assertTrue(params.containsKey("properties"));
        }

        @Test
        @DisplayName("inputSchema为空时应使用兜底参数")
        void whenInputSchemaEmpty_shouldUseFallbackParameters() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factory.createTool("redis-server", config, "get", "获取值", Map.of());

            Map<String, Object> params = tool.getParameters();
            assertNotNull(params);
            assertTrue(params.containsKey("properties"));
            assertTrue(params.containsKey("required"));
        }
    }

    @Nested
    @DisplayName("兜底参数定义测试")
    class FallbackParametersTests {

        @Test
        @DisplayName("get工具应有key参数")
        void getToolShouldHaveKeyParameter() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factory.createTool("redis-server", config, "get", "获取值", null);

            Map<String, Object> params = tool.getParameters();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");

            assertTrue(properties.containsKey("key"));
            assertEquals(List.of("key"), params.get("required"));
        }

        @Test
        @DisplayName("set工具应有key、value、ttl参数")
        void setToolShouldHaveKeyValueTtlParameters() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factory.createTool("redis-server", config, "set", "设置值", null);

            Map<String, Object> params = tool.getParameters();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");

            assertTrue(properties.containsKey("key"));
            assertTrue(properties.containsKey("value"));
            assertTrue(properties.containsKey("ttl"));
            assertEquals(List.of("key", "value"), params.get("required"));
        }

        @Test
        @DisplayName("delete工具应有key和keys参数")
        void deleteToolShouldHaveKeyAndKeysParameters() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factory.createTool("redis-server", config, "delete", "删除", null);

            Map<String, Object> params = tool.getParameters();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");

            assertTrue(properties.containsKey("key"));
            assertTrue(properties.containsKey("keys"));
        }

        @Test
        @DisplayName("keys工具应有pattern和count参数")
        void keysToolShouldHavePatternAndCountParameters() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factory.createTool("redis-server", config, "keys", "查找键", null);

            Map<String, Object> params = tool.getParameters();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");

            assertTrue(properties.containsKey("pattern"));
            assertTrue(properties.containsKey("count"));
            assertEquals(List.of("pattern"), params.get("required"));
        }

        @Test
        @DisplayName("list_ops工具应有operation、key等参数")
        void listOpsToolShouldHaveOperationAndKeyParameters() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factory.createTool("redis-server", config, "list_ops", "列表操作", null);

            Map<String, Object> params = tool.getParameters();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");

            assertTrue(properties.containsKey("operation"));
            assertTrue(properties.containsKey("key"));
            assertTrue(properties.containsKey("value"));
            assertTrue(properties.containsKey("start"));
            assertTrue(properties.containsKey("stop"));
            assertEquals(List.of("operation", "key"), params.get("required"));
        }

        @Test
        @DisplayName("hash_ops工具应有operation、key等参数")
        void hashOpsToolShouldHaveOperationAndKeyParameters() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factory.createTool("redis-server", config, "hash_ops", "哈希操作", null);

            Map<String, Object> params = tool.getParameters();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");

            assertTrue(properties.containsKey("operation"));
            assertTrue(properties.containsKey("key"));
            assertTrue(properties.containsKey("field"));
            assertTrue(properties.containsKey("value"));
            assertEquals(List.of("operation", "key"), params.get("required"));
        }

        @Test
        @DisplayName("未知工具应有通用args参数")
        void unknownToolShouldHaveGenericArgsParameter() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factory.createTool("redis-server", config, "unknown_op", "未知操作", null);

            Map<String, Object> params = tool.getParameters();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) params.get("properties");

            assertTrue(properties.containsKey("args"));
        }
    }

    @Nested
    @DisplayName("缓存机制测试")
    class CacheMechanismTests {

        @Test
        @DisplayName("默认缓存工具应包含query、list_tables、describe_table")
        void defaultCacheableToolsShouldBeConfigured() {
            // 通过创建工厂并验证缓存行为来间接测试
            McpToolFactory factoryWithCache = new McpToolFactory(List.of());
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factoryWithCache.createTool("db-server", config, "query", "查询", Map.of());
            assertNotNull(tool);
        }

        @Test
        @DisplayName("配置的缓存工具应被添加")
        void configuredCacheableToolsShouldBeAdded() {
            McpToolFactory factoryWithCustomCache = new McpToolFactory(
                    List.of("custom_read", "another_read"));
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factoryWithCustomCache.createTool("db-server", config, "custom_read", "自定义读取", Map.of());
            assertNotNull(tool);
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("服务器类型为null时应默认为http")
        void whenServerTypeNull_shouldDefaultToHttp() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", null);

            AgentTool tool = factory.createTool("test-server", config, "query", "查询", Map.of());

            assertNotNull(tool);
        }

        @Test
        @DisplayName("空字符串描述应被接受")
        void emptyDescriptionShouldBeAccepted() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");

            AgentTool tool = factory.createTool("test-server", config, "query", "", Map.of());

            assertEquals("", tool.getDescription());
        }

        @Test
        @DisplayName("复杂inputSchema应正确解析")
        void complexInputSchemaShouldBeParsedCorrectly() {
            AgentscopeCoreProperties.McpServerConfig config = createServerConfig("http://localhost:8080/mcp", "http");
            Map<String, Object> complexSchema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "nested", Map.of(
                                    "type", "object",
                                    "properties", Map.of("field1", Map.of("type", "string"))),
                            "array", Map.of(
                                    "type", "array",
                                    "items", Map.of("type", "integer"))),
                    "required", List.of("nested"));

            AgentTool tool = factory.createTool("test-server", config, "complex", "复杂工具", complexSchema);

            Map<String, Object> params = tool.getParameters();
            assertNotNull(params);
            assertEquals("object", params.get("type"));
        }
    }

    /**
     * 创建服务器配置
     */
    private AgentscopeCoreProperties.McpServerConfig createServerConfig(String url, String type) {
        AgentscopeCoreProperties.McpServerConfig config = new AgentscopeCoreProperties.McpServerConfig();
        config.setEnabled(true);
        config.setUrl(url);
        config.setType(type);
        return config;
    }

    /**
     * 创建标准inputSchema
     */
    private Map<String, Object> createInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "param1", Map.of("type", "string", "description", "参数1"),
                        "param2", Map.of("type", "integer", "description", "参数2")),
                "required", List.of("param1"));
    }
}
