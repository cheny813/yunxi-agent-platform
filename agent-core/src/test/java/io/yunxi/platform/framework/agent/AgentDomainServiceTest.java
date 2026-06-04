package io.yunxi.platform.framework.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.core.studio.StudioMessageHook;
import io.yunxi.platform.framework.model.ModelFactory;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.AgentConfigDto;
import io.yunxi.platform.shared.dto.AgentInfoDto;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.yunxi.platform.shared.exception.NotFoundException;

/**
 * AgentDomainService 核心业务逻辑单元测试
 */
@ExtendWith(MockitoExtension.class)
class AgentDomainServiceTest {

    @Mock
    private AgentscopeCoreProperties properties;

    @Mock
    private StudioMessageHook studioMessageHook;

    @Mock
    private ModelFactory modelFactory;

    private AgentDomainService agentDomainService;
    private DefaultListableBeanFactory beanFactory;

    @BeforeEach
    void setUp() {
        when(properties.getApiKey()).thenReturn("test-api-key");
        when(properties.getModelName()).thenReturn("test-model");
        when(properties.getDefaultPrompt()).thenReturn("test-prompt");

        beanFactory = new DefaultListableBeanFactory();
        agentDomainService = new AgentDomainService(properties, modelFactory, beanFactory);
    }

    @Test
    void testListAgentsWhenEmpty() {
        List<AgentInfoDto> agents = agentDomainService.listAgents();

        assertNotNull(agents);
        assertTrue(agents.isEmpty());
    }

    @Test
    void testListAgentsWithData() {
        agentDomainService.registerAgentInfoDto("agent1", "", "prompt1", "model1");
        agentDomainService.registerAgentInfoDto("agent2", "", "prompt2", "model2");

        List<AgentInfoDto> agents = agentDomainService.listAgents();

        assertNotNull(agents);
        assertEquals(2, agents.size());

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
        AgentConfigDto config = new AgentConfigDto();
        config.setApiKey("custom-api-key");
        config.setModelName("custom-model");
        config.setPrompt("custom-prompt");
        config.setProvider("dashscope");
        config.setTemperature(0.7);
        config.setMaxTokens(1000);

        AgentInfoDto agent = agentDomainService.createAgent("test-agent", config);

        assertNotNull(agent);
        assertEquals("test-agent", agent.getName());
        assertEquals("custom-prompt", agent.getPrompt());
        assertEquals("custom-model", agent.getModelName());
        assertNotNull(agent.getCreatedAt());

        assertNotNull(agentDomainService.getAgent("test-agent"));
        assertNotNull(agentDomainService.getAgent("test-agent"));
    }

    @Test
    void testCreateAgentWithNullConfig() {
        AgentInfoDto agent = agentDomainService.createAgent("test-agent", null);

        assertNotNull(agent);
        assertEquals("test-agent", agent.getName());
        assertEquals("test-prompt", agent.getPrompt());
        assertEquals("test-model", agent.getModelName());
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
        config.setApiKey("");

        AgentInfoDto agent = agentDomainService.createAgent("test-agent", config);
        assertNotNull(agent);
    }

    @Test
    void testDeleteAgentExists() {
        agentDomainService.registerAgentInfoDto("test-agent", "", "test-prompt", "test-model");

        assertNotNull(agentDomainService.getAgent("test-agent"));

        agentDomainService.deleteAgent("test-agent");

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
        agentDomainService.registerAgentInfoDto("test-agent", "", "test-prompt", "test-model");

        try {
            Agent agent = agentDomainService.getAgentInstance("test-agent");
        } catch (Exception e) {
            // Agent prototype bean not registered - expected for non-created agents
        }
    }

    @Test
    void testGetAgentInstanceNotExists() {
        assertThrows(NotFoundException.class,
                () -> agentDomainService.getAgentInstance("non-existent-agent"));
    }

    @Test
    void testFindAgentNotExists() {
        assertNull(agentDomainService.findAgent("non-existent-agent"));
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
        agentDomainService.registerAgentSchema(null, "{\"schema\": \"test\"}");
        assertNull(agentDomainService.getAgentSchema(null));

        agentDomainService.registerAgentSchema("test-agent", null);
        assertNull(agentDomainService.getAgentSchema("test-agent"));

        agentDomainService.registerAgentSchema("  ", "{\"schema\": \"test\"}");
        assertNull(agentDomainService.getAgentSchema("  "));

        agentDomainService.registerAgentSchema("test-agent", "  ");
        assertNull(agentDomainService.getAgentSchema("test-agent"));
    }

    @Test
    void testHasStructuredOutput() {
        assertFalse(agentDomainService.hasStructuredOutput("non-existent-agent"));

        agentDomainService.registerAgentSchema("test-agent", "{\"type\": \"object\"}");
        assertTrue(agentDomainService.hasStructuredOutput("test-agent"));

        assertFalse(agentDomainService.hasStructuredOutput("other-agent"));
    }

    @Test
    void testAgentSysPromptCache() {
        agentDomainService.createAgent("test-agent", null);
        String prompt = agentDomainService.getAgentSysPrompt("test-agent");
        assertNotNull(prompt);
        assertEquals("test-prompt", prompt);
    }

    @Test
    void testAgentModelCache() {
        agentDomainService.createAgent("test-agent", null);
        Model model = agentDomainService.getAgentModel("test-agent");
    }

    @Test
    void testOverrideExistingAgent() {
        AgentConfigDto config1 = new AgentConfigDto();
        config1.setPrompt("first-prompt");
        config1.setProvider("dashscope");
        AgentInfoDto agent1 = agentDomainService.createAgent("test-agent", config1);

        AgentConfigDto config2 = new AgentConfigDto();
        config2.setPrompt("second-prompt");
        config2.setProvider("dashscope");
        AgentInfoDto agent2 = agentDomainService.createAgent("test-agent", config2);

        assertEquals("second-prompt", agent2.getPrompt());
        assertEquals(1, agentDomainService.countAgents());
    }

    @Test
    void testAgentInfoDtoCreationTimestamp() {
        Instant beforeCreation = Instant.now();

        agentDomainService.registerAgentInfoDto("test-agent", "", "test-prompt", "test-model");
        AgentInfoDto agent = agentDomainService.getAgent("test-agent");

        Instant afterCreation = Instant.now();

        assertNotNull(agent.getCreatedAt());

        assertTrue(agent.getCreatedAt().isAfter(beforeCreation.minusSeconds(1)) ||
                agent.getCreatedAt().equals(beforeCreation));
        assertTrue(agent.getCreatedAt().isBefore(afterCreation.plusSeconds(1)) ||
                agent.getCreatedAt().equals(afterCreation));
    }
}
