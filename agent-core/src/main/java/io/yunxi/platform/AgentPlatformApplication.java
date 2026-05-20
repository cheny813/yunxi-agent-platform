package io.yunxi.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 
 * 智能体平台启动入口
 * <p>
 * AgentScope Agent 管理平台
 * </p>
 */

@SpringBootApplication(scanBasePackages = {
        "io.yunxi.platform",
        "io.yunxi.platform.config",
        "io.yunxi.platform.controller"
}, exclude = {
        io.agentscope.runtime.autoconfigure.A2aAutoConfiguration.class
})
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties
@ConfigurationPropertiesScan
@MapperScan({ "io.yunxi.platform.shared.mapper", "io.yunxi.platform.framework.session.mapper",
        "io.yunxi.platform.framework.profile", "io.yunxi.platform.framework.security",
        "io.yunxi.platform.business.rule" })
public class AgentPlatformApplication {

    /**
     * 应用主入口
     *
     * @param args 启动参数
     */

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);

    }
}
