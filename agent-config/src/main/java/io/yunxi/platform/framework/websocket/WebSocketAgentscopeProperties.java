package io.yunxi.platform.framework.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * WebSocket Agent Scope 配置属性类
 * 用于配置WebSocket通信相关的超时和限制参数
 */
@Component
@ConfigurationProperties(prefix = "agentscope.websocket")
public class WebSocketAgentscopeProperties {
    
    /**
     * 默认Agent超时时间（毫秒）
     */
    private Long defaultAgentTimeout = 60000L;
    
    /**
     * 连接超时时间（毫秒）
     */
    private Integer connectionTimeout = 30000;
    
    /**
     * 读取超时时间（毫秒）
     */
    private Integer readTimeout = 60000;
    
    /**
     * 写入超时时间（毫秒）
     */
    private Integer writeTimeout = 30000;
    
    /**
     * 最大连接数
     */
    private Integer maxConnections = 100;
    
    /**
     * 心跳间隔时间（毫秒）
     */
    private Integer heartbeatInterval = 30000;

    // Getter and Setter methods
    public Long getDefaultAgentTimeout() {
        return defaultAgentTimeout;
    }

    public void setDefaultAgentTimeout(Long defaultAgentTimeout) {
        this.defaultAgentTimeout = defaultAgentTimeout;
    }

    public Integer getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Integer connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Integer getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Integer readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Integer getWriteTimeout() {
        return writeTimeout;
    }

    public void setWriteTimeout(Integer writeTimeout) {
        this.writeTimeout = writeTimeout;
    }

    public Integer getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(Integer maxConnections) {
        this.maxConnections = maxConnections;
    }

    public Integer getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Integer heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }
}