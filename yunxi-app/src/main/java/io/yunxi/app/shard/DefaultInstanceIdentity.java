package io.yunxi.app.shard;

import java.net.UnknownHostException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 默认实例身份：基于 {@code spring.application.name} + 主机名 + {@code server.port} 计算
 * {@code app-host-port}。与 aistio 的 fallback 分支同款，且不依赖 aistio 是否启用（解耦条件装配）。
 */
@Component
public class DefaultInstanceIdentity implements InstanceIdentity {

    private final Environment environment;

    public DefaultInstanceIdentity(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String instanceKey() {
        String app = environment.getProperty("spring.application.name", "yunxi");
        String host = resolveHost();
        String port = environment.getProperty("server.port", "8080");
        return app + "-" + host + "-" + port;
    }

    private String resolveHost() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
