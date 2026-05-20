package io.yunxi.platform.framework.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.studio.StudioMessageHook;
import io.yunxi.platform.framework.embedding.ChatModelProvider;
import io.yunxi.platform.framework.embedding.ModelConfig;
import io.yunxi.platform.framework.embedding.ModelProviderFactory;
import io.yunxi.platform.shared.dto.AgentConfigDto;
import io.yunxi.platform.shared.dto.AgentInfoDto;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.yunxi.platform.shared.exception.NotFoundException;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;

/**
 * AgentDomainService 核心业务逻辑单元测试
 */
@ExtendWith(MockitoExtension.class)
class AgentDomainServiceTest {

    @Mock
    private AgentscopeCoreProperties properties;

    @Mock
    private ModelProviderFactory modelFactory;

    @Mock
    private ChatModelProvider chatModelProvider;

    @Mock
    private StudioMessageHook studioMessageHook;

    private AgentDomainService agentDomainService;

    @BeforeEach
    void setUp() {
        // 设置模拟对象
        when(properties.getApiKey()).thenReturn("test-api-key");
        when(properties.getModelName()).thenReturn("test-model");
        when(properties.getDefaultPrompt()).thenReturn("test-prompt");

        agentDomainService = new AgentDomainService(properties, modelFactory);

        // 使用反射注入StudioMessageHook
        try {
            var field = AgentDomainService.class.getDeclaredField("studioMessageHook");
            field.setAccessible(true);
            field.set(agentDomainService, studioMessageHook);
        } catch (Exception e) {
            // 注入失败不影响主要测试
        }
    }

    @Test
    void testListAgentsWhenEmpty() {
        List<AgentInfoDto> agents = agentDomainService.listAgents();

        assertNotNull(agents);
        assertTrue(agents.isEmpty());
    }

    @Test
    void testListAgentsWithData() {
        // 预注册一些Agent信息
        agentDomainService.registerAgentInfoDto("agent1", "", "prompt1", "model1");
        agentDomainService.registerAgentInfoDto("agent2", "", "prompt2", "model2");

        List<AgentInfoDto> agents = agentDomainService.listAgents();

        assertNotNull(agents);
        assertEquals(2, agents.size());

        // 验证Agent信息
        AgentInfoDto agent1 = agents.stream()
                .filter(a -> "agent1".equals(a.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("prompt1", agent1.getPrompt());
        assertEquals("model1", agent1.getModelName());
    }

    @Test
    void testGetAgentExists() {
        agentDomainService.registerAgentInfoDto("test-agent", "", "test-prompt", "test-model");

        AgentInfoDto agent = agentDomainService.getAgent("test-agent");

        assertNotNull(agent);
        assertEquals("test-agent", agent.getName());
        assertEquals("test-prompt", agent.getPrompt());
        assertEquals("test-model", agent.getModelName());
    }

    @Test
    void testGetAgentNotExists() {
        assertThrows(NotFoundException.class,
                () -> agentDomainService.getAgent("non-existent-agent"));
    }

    @Test
    void testCreateAgentWithValidConfig() {
        // 设置模型工厂模拟
        when(modelFactory.createProvider(any(ModelConfig.class))).thenReturn(chatModelProvider);

        AgentConfigDto config = new AgentConfigDto();
        config.setApiKey("custom-api-key");
        config.setModelName("custom-model");
        config.setPrompt("custom-prompt");
        config.setProvider("custom-provider");
        config.setTemperature(0.7);
        config.setMaxTokens(1000);

        AgentInfoDto agent = agentDomainService.createAgent("test-agent", config);

        assertNotNull(agent);
        assertEquals("test-agent", agent.getName());
        assertEquals("custom-prompt", agent.getPrompt());
        assertEquals("custom-model", agent.getModelName());
        assertNotNull(agent.getCreatedAt());

        // 验证缓存中已存在
        assertNotNull(agentDomainService.getAgent("test-agent"));
        assertNotNull(agentDomainService.getReActAgent("test-agent"));
    }

    @Test
    void testCreateAgentWithNullConfig() {
        when(modelFactory.createProvider(any(ModelConfig.class))).thenReturn(chatModelProvider);

        AgentInfoDto agent = agentDomainService.createAgent("test-agent", null);

        assertNotNull(agent);
        assertEquals("test-agent", agent.getName());
        assertEquals("test-prompt", agent.getPrompt()); // 使用默认值
        assertEquals("test-model", agent.getModelName()); // 使用默认值
    }

    @Test
    void testCreateAgentWithNullName() {
        assertThrows(BadRequestException.class,
                () -> agentDomainService.createAgent(null, new AgentConfigDto()));
    }

    @Test
    void testCreateAgentWithBlankName() {
        assertThrows(BadRequestException.class,
                () -> agentDomainService.createAgent("  ", new AgentConfigDto()));
    }

    @Test
    void testCreateAgentWithMissingApiKey() {
        when(properties.getApiKey()).thenReturn(null);

        AgentConfigDto config = new AgentConfigDto();
        config.setApiKey(""); // 空字符串

        assertThrows(BadRequestException.class,
                () -> agentDomainService.createAgent("test-agent", config));
    }

    @Test
    void testDeleteAgentExists() {
        agentDomainService.registerAgentInfoDto("test-agent", "", "test-prompt", "test-model");

        // 确保Agent存在
        assertNotNull(agentDomainService.getAgent("test-agent"));

        // 删除Agent
        agentDomainService.deleteAgent("test-agent");

        // 验证Agent已删除
        assertThrows(NotFoundException.class,
                () -> agentDomainService.getAgent("test-agent"));
    }

    @Test
    void testDeleteAgentNotExists() {
        assertThrows(NotFoundException.class,
                () -> agentDomainService.deleteAgent("non-existent-agent"));
    }

    @Test
    void testGetAgentInstanceExists() {
        // 创建Agent实例
        when(modelFactory.createProvider(any(ModelConfig.class))).thenReturn(chatModelProvider);
        agentDomainService.createAgent("test-agent", null);

        ReActAgent agent = agentDomainService.getAgentInstance("test-agent");

        assertNotNull(agent);
    }

    @Test
    void testGetAgentInstanceNotExists() {
        assertThrows(NotFoundException.class,
                () -> agentDomainService.getAgentInstance("non-existent-agent"));
    }

    @Test
    void testGetReActAgentExists() {
        when(modelFactory.createProvider(any(ModelConfig.class))).thenReturn(chatModelProvider);
        agentDomainService.createAgent("test-agent", null);

        ReActAgent agent = agentDomainService.getReActAgent("test-agent");

        assertNotNull(agent);
    }

    @Test
    void testGetReActAgentNotExists() {
        assertNull(agentDomainService.getReActAgent("non-existent-agent"));
    }

    @Test
    void testCountAgents() {
        assertEquals(0, agentDomainService.countAgents());

        agentDomainService.registerAgentInfoDto("agent1", "", "prompt1", "model1");
        assertEquals(1, agentDomainService.countAgents());

        agentDomainService.registerAgentInfoDto("agent2", "", "prompt2", "model2");
        assertEquals(2, agentDomainService.countAgents());
    }

    @Test
    void testRegisterAgentSchema() {
        agentDomainService.registerAgentSchema("test-agent", "{\"type\": \"object\"}");

        String schema = agentDomainService.getAgentSchema("test-agent");
        assertNotNull(schema);
        assertEquals("{\"type\": \"object\"}", schema);

        assertTrue(agentDomainService.hasStructuredOutput("test-agent"));
    }

    @Test
    void testRegisterAgentSchemaWithNullValues() {
        // null名称 - 应该不注册
        agentDomainService.registerAgentSchema(null, "{\"schema\": \"test\"}");
        assertNull(agentDomainService.getAgentSchema(null));

        // null schema - 应该不注册
        agentDomainService.registerAgentSchema("test-agent", null);
        assertNull(agentDomainService.getAgentSchema("test-agent"));

        // 空白名称 - 应该不注册
        agentDomainService.registerAgentSchema("  ", "{\"schema\": \"test\"}");
        assertNull(agentDomainService.getAgentSchema("  "));

        // 空白 schema - 应该不注册
        agentDomainService.registerAgentSchema("test-agent", "  ");
        assertNull(agentDomainService.getAgentSchema("test-agent"));
    }

    @Test
    void testHasStructuredOutput() {
        // 初始状态
        assertFalse(agentDomainService.hasStructuredOutput("non-existent-agent"));

        // 注册后
        agentDomainService.registerAgentSchema("test-agent", "{\"type\": \"object\"}");
        assertTrue(agentDomainService.hasStructuredOutput("test-agent"));

        // 不存在的Agent
        assertFalse(agentDomainService.hasStructuredOutput("other-agent"));
    }

    @Test
    void testGetAgentToolkitReflectionSafety() {
        // 该方法依赖反射和运行环境，主要测试异常处理

        // 测试不存在Agent的情况
        assertNull(agentDomainService.getAgentToolkit("non-existent-agent"));

        // TODO: 更详细的反射测试需要Mock复杂的内部结构
        // 在实际环境中，这个方法的测试需要更复杂的设置
    }

    @Test
    void testOverrideExistingAgent() {
        when(modelFactory.createProvider(any(ModelConfig.class))).thenReturn(chatModelProvider);

        // 第一次创建
        AgentConfigDto config1 = new AgentConfigDto();
        config1.setPrompt("first-prompt");
        AgentInfoDto agent1 = agentDomainService.createAgent("test-agent", config1);

        // 修改配置后再次创建（覆盖）
        AgentConfigDto config2 = new AgentConfigDto();
        config2.setPrompt("second-prompt");
        AgentInfoDto agent2 = agentDomainService.createAgent("test-agent", config2);

        // 验证使用新配置
        assertEquals("second-prompt", agent2.getPrompt());

        // Agent数量应该不变（重用同一个名称）
        assertEquals(1, agentDomainService.countAgents());
    }

    @Test
    void testModelFactoryInteraction() {
        // 验证模型工厂被正确调用
        when(modelFactory.createProvider(any(ModelConfig.class))).thenReturn(chatModelProvider);

        AgentDomainService service = new AgentDomainService(properties, modelFactory);
        service.createAgent("test-agent", null);

        // 验证模型工厂被调用
        verify(modelFactory, times(1)).createProvider(any(ModelConfig.class));
    }

    @Test
    void testStudioMessageHookInjection() {
        when(modelFactory.createProvider(any(ModelConfig.class))).thenReturn(chatModelProvider);

        // 注入Studio Hook的服务
        AgentDomainService serviceWithHook = new AgentDomainService(properties, modelFactory);

        // 使用反射注入hook
        try {
            var field = AgentDomainService.class.getDeclaredField("studioMessageHook");
            field.setAccessible(true);
            field.set(serviceWithHook, studioMessageHook);
        } catch (Exception e) {
            // 跳过hook相关测试
            return;
        }

        // 创建Agent，应该使用hook
        serviceWithHook.createAgent("test-agent", null);

        // TODO: 验证hook是否被调用（需要更复杂的ReActAgent模拟）
    }

    @Test
    void testAgentInfoDtoCreationTimestamp() {
        Instant beforeCreation = Instant.now();

        agentDomainService.registerAgentInfoDto("test-agent", "", "test-prompt", "test-model");
        AgentInfoDto agent = agentDomainService.getAgent("test-agent");

        Instant afterCreation = Instant.now();

        assertNotNull(agent.getCreatedAt());

        // 验证创建时间在前后时间范围内
        assertTrue(agent.getCreatedAt().isAfter(beforeCreation.minusSeconds(1)) ||
                agent.getCreatedAt().equals(beforeCreation));
        assertTrue(agent.getCreatedAt().isBefore(afterCreation.plusSeconds(1)) ||
                agent.getCreatedAt().equals(afterCreation));
    }
}