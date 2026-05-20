package io.yunxi.platform.business.a2apipeline;

import io.yunxi.platform.business.a2apipeline.config.A2ACodePipelineProperties;
import io.yunxi.platform.business.a2apipeline.config.A2APipelineStateConfig;
import io.yunxi.platform.infra.monitoring.PipelineMetricsCollector;
import io.yunxi.platform.business.a2apipeline.registry.AgentRegistry;
import io.yunxi.platform.business.a2apipeline.service.CodeFixPipelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A2A 代码修复流水线启动类
 * <p>
 * 消息和安全功能由框架层统一管理。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@SpringBootApplication
@EnableAsync
@EnableScheduling
@ComponentScan(basePackages = "io.yunxi.platform.business.a2apipeline")
public class CodeFixPipelineApplication {

    /**
     * 应用入口方法
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(CodeFixPipelineApplication.class, args);
    }

    /**
     * 启动初始化，打印流水线启动信息
     *
     * @param properties       流水线配置属性
     * @param stateConfig      流水线状态配置
     * @param agentRegistry    Agent 注册表
     * @param metricsCollector 流水线指标收集器
     * @param pipelineService  代码修复流水线服务
     * @return 命令行运行器
     */
    @Bean
    public CommandLineRunner initializePipeline(
            A2ACodePipelineProperties properties,
            A2APipelineStateConfig stateConfig,
            AgentRegistry agentRegistry,
            PipelineMetricsCollector metricsCollector,
            CodeFixPipelineService pipelineService) {

        return args -> {
            log.info("========================================");
            log.info("A2A Code Fix Pipeline Starting...");
            log.info("========================================");

            if (properties.isEnabled()) {
                log.info("[INIT] Pipeline enabled");
            }

            log.info("========================================");
            log.info("A2A Code Fix Pipeline Started Successfully");
            log.info("========================================");
        };
    }
}
