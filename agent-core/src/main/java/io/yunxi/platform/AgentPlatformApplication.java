package io.yunxi.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 平台主入口
 * <p>
 * AgentScope Agent 管理平台
 * </p>
 */

import io.agentscope.spring.boot.a2a.AgentscopeA2aAutoConfiguration;

@SpringBootApplication(scanBasePackages = {
                "io.yunxi.platform",
                "io.yunxi.platform.config",
                "io.yunxi.platform.controller"
}, exclude = {
                AgentscopeA2aAutoConfiguration.class
})
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties
@ConfigurationPropertiesScan
@MapperScan({ "io.yunxi.platform.shared.mapper", "io.yunxi.platform.session.mapper",
                "io.yunxi.platform.agent.profile", "io.yunxi.platform.security.audit",
                "io.yunxi.platform.business.rule" })
public class AgentPlatformApplication {

        /**
         * 应用入口
         *
         * @param args 启动参数
         */

        public static void main(String[] args) {
                SpringApplication.run(AgentPlatformApplication.class, args);

        }
}
