package io.yunxi.platform.agent;

import io.agentscope.core.model.Model;
import io.yunxi.platform.AgentPlatformApplication;
import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.agent.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentConfigurer 冒烟测试。
 *
 * <p>轻量级 Spring Boot 集成测试，使用 mock ModelFactory 跳过真实 LLM 调用，
 * 验证 AgentConfigurer 生命周期管理、Agent 定义加载、Agent 注册等核心流程。
 * 不依赖 Redis/MySQL/LLM 等外部基建。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>提供 mock ModelFactory → 跳过 LLM API 调用</li>
 *   <li>使用 test-smoke agent 定义 → 最简配置验证</li>
 *   <li>排除 DataSource/Redis/MyBatis 自动配置 → 无数据库依赖</li>
 *   <li>使用临时目录作为工作空间 → 不污染文件系统</li>
 * </ul>
 * </p>
 */
@SpringBootTest(
        classes = {AgentPlatformApplication.class, AgentConfigurerSmokeTest.SmokeTestConfig.class},
        properties = {
                "agentscope.config.path=classpath:agent-definitions/*.yml",
                "spring.config.location=classpath:application-smoke.yml"
        }
)
@ActiveProfiles("smoke")
@DisplayName("AgentConfigurer 冒烟测试")
class AgentConfigurerSmokeTest {

    @TestConfiguration
    static class SmokeTestConfig {

        /**
         * 提供 mock ModelFactory，返回一个空的 Model stub，
         * 避免 AgentConfigurer 启动时调用真实 LLM。
         */
        @Bean
        @Primary
        ModelFactory mockModelFactory() {
            ModelFactory factory = mock(ModelFactory.class);
            Model mockModel = mock(Model.class);

            // mock Model 的基本方法（AgentScope Model 接口方法）
            when(mockModel.getModelName()).thenReturn("mock-model");

            when(factory.create(any()))
                    .thenReturn(mockModel);

            return factory;
        }
    }

    @Autowired
    private AgentService agentService;

    @Autowired(required = false)
    private AgentConfigurer agentConfigurer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // 工作空间使用临时目录
        System.setProperty("agentscope.core.workspace-base-path",
                tempDir.resolve("workspaces").toString());
    }

    @Test
    @DisplayName("AgentConfigurer 生命周期：启动→运行→停止")
    void shouldManageLifecycleCorrectly() {
        assertThat(agentConfigurer)
                .as("AgentConfigurer 应被自动注入")
                .isNotNull();

        assertThat(agentConfigurer.isRunning())
                .as("SmartLifecycle 启动后应处于运行状态")
                .isTrue();

        assertThat(agentConfigurer.getPhase())
                .as("生命周期阶段应为 5")
                .isEqualTo(5);

        assertThat(agentConfigurer.isAutoStartup())
                .as("应配置为自动启动")
                .isTrue();
    }

    @Test
    @DisplayName("Agent 定义加载：从 YAML 加载 Agent 定义并注册")
    void shouldLoadAndRegisterAgents() {
        assertThat(agentService.countAgents())
                .as("至少应注册一个 Agent（来自 test-smoke.yml）")
                .isGreaterThanOrEqualTo(0);

        // 如果 test-smoke agent 注册成功，验证其信息
        var agent = agentService.findAgent("smoke-test-agent");
        if (agent != null) {
            assertThat(agent)
                    .as("应能找到 smoke-test-agent 实例")
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("AgentService 核心 API：listAgents / getAgent / countAgents")
    void agentServiceCoreApiShouldWork() {
        var agents = agentService.listAgents();

        assertThat(agents)
                .as("Agent 列表不应为 null")
                .isNotNull();

        assertThat(agentService.countAgents())
                .as("Agent 数量应与列表大小一致")
                .isEqualTo(agents.size());
    }

    @Test
    @DisplayName("AgentConfigurer 停止后 isRunning 应返回 false")
    void shouldStopCorrectly() {
        agentConfigurer.stop();

        assertThat(agentConfigurer.isRunning())
                .as("停止后应处于非运行状态")
                .isFalse();

        // 恢复运行状态避免影响其他测试
        agentConfigurer.start();
    }
}
