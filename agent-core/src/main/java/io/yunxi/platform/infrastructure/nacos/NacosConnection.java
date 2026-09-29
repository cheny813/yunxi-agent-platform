package io.yunxi.platform.infrastructure.nacos;

/**
 * Nacos 连接参数归一化载体。
 * <p>MCP 协调底座（McpNacosProperties）与 A2A 注册（A2AConfig）两套配置均映射到此，
 * 由 NacosClientFactory 统一构造客户端，避免各处重复拼接 Properties。</p>
 */
public class NacosConnection {

    private final String serverAddr;
    private final String namespace;
    private final String username;
    private final String password;

    public NacosConnection(String serverAddr, String namespace, String username, String password) {
        this.serverAddr = serverAddr;
        this.namespace = namespace;
        this.username = username;
        this.password = password;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
