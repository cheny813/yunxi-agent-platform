package io.yunxi.platform.config;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.ConnectConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 客户端配置
 *
 * <p>
 * 当配置 milvus.enabled=true 时自动创建 MilvusClientV2 Bean
 * </p>
 *
 * <p>
 * <b>SDK版本</b>：3.0.x，使用 {@link ConnectConfig#builder()} 方式构建连接配置，
 * 包路径沿用 io.milvus.v2.*（v2 表示 Milvus V2 API 版本，非 SDK 版本）
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Configuration
public class MilvusClientConfig {

    /**
     * 创建 Milvus 客户端 Bean
     *
     * @param config Milvus 配置属性
     * @return MilvusClientV2 实例，初始化失败时返回 null
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
    public MilvusClientV2 milvusClient(MilvusConfig config) {
        log.info("初始化 Milvus 客户端: {}:{}", config.getHost(), config.getPort());

        try {
            // Milvus SDK 3.0.x 连接配置
            ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                    .uri("http://" + config.getHost() + ":" + config.getPort())
                    .dbName(config.getDatabase())
                    .connectTimeoutMs(config.getConnectTimeout() * 1000L);

            // 配置认证（如果提供了凭据）
            // 优先使用 token，其次使用 username/password
            if (config.getToken() != null && !config.getToken().isEmpty()) {
                log.info("Milvus 使用 Token 认证");
                builder.token(config.getToken());
            } else if (config.getUsername() != null && !config.getUsername().isEmpty()) {
                log.info("Milvus 使用用户名密码认证: {}", config.getUsername());
                builder.username(config.getUsername())
                        .password(config.getPassword() != null ? config.getPassword() : "");
            } else {
                log.info("Milvus 无认证模式");
            }

            ConnectConfig connectConfig = builder.build();
            MilvusClientV2 client = new MilvusClientV2(connectConfig);
            log.info("Milvus 客户端初始化成功");
            return client;

        } catch (Exception e) {
            log.error("Milvus 客户端初始化失败: {}", e.getMessage(), e);
            // 返回null允许应用启动，向量功能将禁用
            return null;
        }
    }
}
