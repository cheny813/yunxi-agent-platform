package io.yunxi.app.shard;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本实例向 Nacos 注册（携带 {@code baseUrl} metadata，供跨实例转发寻址）。
 *
 * <p>实例身份经 {@link InstanceIdentity}（与 aistio fallback 同款的 {@code app-host-port}）。
 * {@code baseUrl} 优先取 {@code yunxi.aistio.base-url}，否则推导 {@code http://{host}:{port}}（与
 * {@link InstanceIdentity} 的 host 解析同源）。进程退出由 {@link #destroy()}（DisposableBean）触发优雅下线，
 * 使其他实例经订阅增量刷新哈希环。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.shard.enabled", havingValue = "true")
public class ShardInstanceRegistrar implements DisposableBean {

    private final NamingService namingService;
    private final String serviceName;
    private final String instanceId;
    private final String ip;
    private final int port;
    private final String baseUrl;

    public ShardInstanceRegistrar(NamingService namingService,
                                  InstanceIdentity identity,
                                  @Value("${yunxi.shard.service-name:yunxi-runtime}") String serviceName,
                                  @Value("${yunxi.aistio.base-url:}") String configuredBaseUrl,
                                  @Value("${yunxi.shard.self-ip:}") String configuredIp,
                                  @Value("${yunxi.shard.self-port:${server.port:8080}}") int port) {
        this.namingService = namingService;
        this.serviceName = serviceName;
        this.instanceId = identity.instanceKey();
        String resolvedHost = (configuredIp != null && !configuredIp.isBlank())
                ? configuredIp : resolveHost();
        this.ip = resolvedHost;
        this.port = port;
        this.baseUrl = (configuredBaseUrl != null && !configuredBaseUrl.isBlank())
                ? configuredBaseUrl : "http://" + resolvedHost + ":" + port;
    }

    /** 向 Nacos 注册本实例（携带 baseUrl metadata）。 */
    public void register() throws Exception {
        Instance inst = new Instance();
        inst.setServiceName(serviceName);
        inst.setInstanceId(instanceId);
        inst.setIp(ip);
        inst.setPort(port);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("instanceId", instanceId);
        metadata.put("baseUrl", baseUrl);
        inst.setMetadata(metadata);
        namingService.registerInstance(serviceName, inst);
    }

    /** 优雅下线：注销本实例，触发其他实例环增量刷新。 */
    @Override
    public void destroy() throws Exception {
        namingService.deregisterInstance(serviceName, ip, port);
    }

    private String resolveHost() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "localhost";
        }
    }
}
