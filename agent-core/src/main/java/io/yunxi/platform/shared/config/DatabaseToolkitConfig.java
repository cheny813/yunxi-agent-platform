package io.yunxi.platform.shared.config;

import io.yunxi.platform.shared.util.database.DatabaseToolkit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Database Toolkit 配置类
 * <p>
 * 配置 DatabaseToolkit 为 Spring Bean
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Configuration
public class DatabaseToolkitConfig {

    /**
     * 创建 DatabaseToolkit Bean
     *
     * @return DatabaseToolkit 实例
     */
    @Bean
    public DatabaseToolkit databaseToolkit() {
        return DatabaseToolkit.builder()
                .restTemplate(new RestTemplate())
                .build();
    }
}
