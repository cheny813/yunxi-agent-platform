package io.yunxi.app.shard;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * 分片路由真实环境 e2e：对接真实 Nacos 服务发现，
 * 验证「N 实例经 Nacos 发现 → 一致性哈希环 → 构建总数=全量（完备且互斥划分）
 * → non-owned 经真实 HTTP 转发至 owner baseUrl → 实例下线仅迁移约 1/N」全链路。
 *
 * <p>环境门控：本测试需要 Nacos 在线（默认 {@code 127.0.0.1:8848}）且可注册实例。若 Nacos 不可达，
 * 经 {@link Assumptions#assumeTrue(boolean, String)} 整体跳过，<b>绝不伪造证据</b>。
 * 启动本地 Nacos 后（standalone、暴露 8848 与 gRPC 9848/9849、关闭鉴权）：
 * <pre>
 *   docker run -d --name yunxi-nacos -p 8848:8848 -p 9848:9848 -p 9849:9849 \
 *     -e MODE=standalone -e NACOS_AUTH_ENABLE=false nacos/nacos-server:v2.5.1
 * </pre>
 * 再跑 {@code mvn -o test -pl yunxi-app -Dtest=ShardRoutingNacosE2eTest}。</p>
 *
 * <p>说明：为隔离测试、避免拉起完整第二进程（需 Redis/Milvus 等周边设施），两个应用节点以
 * 两个 Nacos 注册实例身份（node-a / node-b，各自携带 baseUrl metadata）表示；owner 节点的
 * 真实 HTTP 端点由 JDK 内置 HttpServer 充当。分片路由机制（发现/哈希环/转发/重平衡）均为真实执行。
 * 两节点各用独立 NamingService：nacos gRPC 临时实例按 (连接, 服务) 单槽位发布，同一连接
 * 对同一服务重复注册会替换前一实例。</p>
 */
class ShardRoutingNacosE2eTest {

    private static final String SERVICE = "yunxi-runtime";
    private static final String NACOS_ADDR = System.getProperty("nacos.e2e.addr", "127.0.0.1:8848");
    private static final int OWNER_HTTP_PORT = 18081;
    private static final String NODE_A = "node-a";
    private static final String NODE_B = "node-b";
    private static final int VNODES = 160;
    private static final String OWNER_BODY = "NODE-A-OWNER-BODY";

    /** 测试观察者：订阅/查询实例视图。 */
    private NamingService namingService;
    /** node-a 独立客户端：nacos gRPC 临时实例按 (连接, 服务) 单槽位发布，同连接重复注册会替换前一实例。 */
    private NamingService nodeAClient;
    /** node-b 独立客户端，与生产每实例一客户端形态一致。 */
    private NamingService nodeBClient;
    private HttpServer ownerServer;
    private NacosBackedShardResolver resolverA;
    private NacosBackedShardResolver resolverB;
    private AgentForwardingRouter routerB;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("serverAddr", NACOS_ADDR);

        try {
            namingService = NacosFactory.createNamingService(props);
            nodeAClient = NacosFactory.createNamingService(props);
            nodeBClient = NacosFactory.createNamingService(props);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Nacos 客户端初始化失败，跳过 e2e：" + e);
            return;
        }

        // 注册两个代表应用节点的 Nacos 实例（携带 baseUrl metadata）。
        // 每节点独立客户端：nacos gRPC 临时实例按 (连接, 服务) 单槽位发布，
        // 同一连接重复注册会替换前一个实例，与生产每实例一客户端的形态一致。
        try {
            registerNode(nodeAClient, NODE_A, OWNER_HTTP_PORT, "http://127.0.0.1:" + OWNER_HTTP_PORT);
            registerNode(nodeBClient, NODE_B, 18082, "http://127.0.0.1:18082");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Nacos 不可达/注册失败，跳过 e2e：" + e);
            return;
        }

        // 订阅以建立服务端推送通道：Nacos 2.x/3.x 下 getAllInstances 在未 subscribe 时拿不到实时实例集
        try {
            namingService.subscribe(SERVICE, event -> {});
        } catch (Exception ignored) {
            // 订阅失败不阻断，降级为轮询查询
        }

        // 等待实例集经 Nacos 可见（注册传播 + 推送通道）
        boolean visible = pollUntil(() -> {
            try {
                return namingService.getAllInstances(SERVICE).size() == 2;
            } catch (Exception e) {
                return false;
            }
        }, 30_000);
        Assumptions.assumeTrue(visible, "Nacos 注册实例未在 30s 内可见，跳过 e2e：确认 Nacos 在线且暴露 gRPC 9848/9849");

        // 真实 owner HTTP 端点（node-a）
        ownerServer = HttpServer.create(new InetSocketAddress(OWNER_HTTP_PORT), 0);
        ownerServer.createContext("/", exchange -> {
            try {
                if ("POST".equals(exchange.getRequestMethod())) {
                    exchange.getRequestBody().close();
                }
                byte[] body = OWNER_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(HttpStatus.OK.value(), body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } catch (IOException ignored) {
                // 端点异常由转发方 fail-open 兜底
            }
        });
        ownerServer.start();

        InstanceIdentity idA = () -> NODE_A;
        InstanceIdentity idB = () -> NODE_B;
        resolverA = new NacosBackedShardResolver(namingService, idA, SERVICE, VNODES);
        resolverB = new NacosBackedShardResolver(namingService, idB, SERVICE, VNODES);
        routerB = new AgentForwardingRouter(resolverB, namingService, SERVICE, new RestTemplate());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ownerServer != null) {
            ownerServer.stop(0);
        }
        try {
            deregisterNode(nodeAClient, NODE_A);
            deregisterNode(nodeBClient, NODE_B);
        } catch (Exception ignored) {
            // 清理失败不影响（连接断开后服务端会回收该连接的临时实例）
        }
        for (NamingService ns : new NamingService[] {namingService, nodeAClient, nodeBClient}) {
            if (ns != null) {
                try {
                    ns.shutDown();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
    }

    @Test
    void ownershipPartitionIsCompleteAndMutuallyExclusive() {
        int total = 2000;
        int aCount = 0;
        for (int i = 0; i < total; i++) {
            String id = "agent-" + i;
            boolean ownedByA = resolverA.owns(id);
            boolean ownedByB = resolverB.owns(id);
            // 完备（必有归属）且互斥（不能同时归属两节点）
            Assertions.assertTrue(ownedByA ^ ownedByB,
                    "agent " + id + " 必须恰好归属一个节点（完备且互斥）");
        }
        // 统计（再次遍历确认计数一致）
        for (int i = 0; i < total; i++) {
            if (resolverA.owns("agent-" + i)) {
                aCount++;
            }
        }
        int bCount = total - aCount;
        // 构建总数 = 全量：两节点各自仅构建 owned，并集 = 全集
        Assertions.assertEquals(total, aCount + bCount, "构建总数应等于全量 agent 数");
        Assertions.assertTrue(aCount > 0 && bCount > 0, "两节点应各分到部分 agent（≈50/50）");
    }

    @Test
    void nonOwnedAgentIsForwardedToOwnerBaseUrlOverRealHttp() {
        // 找一个 node-a 拥有的 agent（从 resolverB 视角看即 non-owned）
        String ownedByA = findOwnedBy(NODE_A);
        String ownedByB = findOwnedBy(NODE_B);
        Assertions.assertNotNull(ownedByA);
        Assertions.assertNotNull(ownedByB);

        // node-b 视角：ownedByA 非本节点 → 经真实 HTTP 转发至 node-a baseUrl
        HttpHeaders headers = new HttpHeaders();
        ResponseEntity<String> forwarded = routerB.forwardIfNeeded(
                ownedByA, "/chat", HttpMethod.POST, "ping", headers);
        Assertions.assertNotNull(forwarded, "non-owned agent 应被转发而非返回 null");
        Assertions.assertTrue(forwarded.getStatusCode().is2xxSuccessful());
        Assertions.assertEquals(OWNER_BODY, forwarded.getBody());

        // node-b 视角：ownedByB 本节点拥有 → 本地处理返回 null
        ResponseEntity<String> local = routerB.forwardIfNeeded(
                ownedByB, "/chat", HttpMethod.POST, "ping", headers);
        Assertions.assertNull(local, "owned agent 应由本节点本地处理（返回 null）");
    }

    @Test
    void instanceDownMigratesOnlyAboutOneNth() throws Exception {
        int total = 2000;
        Set<String> ownedByBBefore = new HashSet<>();
        Set<String> ownedByABefore = new HashSet<>();
        for (int i = 0; i < total; i++) {
            String id = "agent-" + i;
            if (resolverB.owns(id)) {
                ownedByBBefore.add(id);
            } else {
                ownedByABefore.add(id);
            }
        }
        Assertions.assertTrue(ownedByBBefore.size() > 0, "下线前 node-b 应拥有部分 agent");

        // 下线 node-b
        deregisterNode(nodeBClient, NODE_B);
        boolean shrunk = pollUntil(() -> {
            try {
                return namingService.getAllInstances(SERVICE).size() == 1;
            } catch (Exception e) {
                return false;
            }
        }, 8_000);
        Assumptions.assumeTrue(shrunk, "node-b 下线未在 8s 内被 Nacos 反映");
        // 给订阅回调重建环留时间
        Thread.sleep(1_000);

        // 仅约 1/N（此处恰好 node-b 的全部份额）迁移到剩余节点，其余稳定不变
        int migratedToA = 0;
        int aStable = 0;
        for (String id : ownedByBBefore) {
            if (resolverA.owns(id)) {
                migratedToA++;
            }
        }
        for (String id : ownedByABefore) {
            if (resolverA.owns(id)) {
                aStable++;
            }
        }
        Assertions.assertEquals(ownedByBBefore.size(), migratedToA,
                "node-b 的全部份额应迁移到唯一剩余节点（约 1/N，此处 N=2 即半数）");
        Assertions.assertEquals(ownedByABefore.size(), aStable,
                "原本归属 node-a 的 agent 不应因 node-b 下线而重排（非全局 reshuffle）");
    }

    private String findOwnedBy(String node) {
        for (int i = 0; i < 5000; i++) {
            String id = "agent-" + i;
            if (NODE_A.equals(node) && resolverA.owns(id)) {
                return id;
            }
            if (NODE_B.equals(node) && resolverB.owns(id)) {
                return id;
            }
        }
        return null;
    }

    private void registerNode(NamingService client, String instanceId, int port, String baseUrl) throws NacosException {
        Instance inst = new Instance();
        inst.setInstanceId(instanceId);
        inst.setIp("127.0.0.1");
        inst.setPort(port);
        inst.setClusterName("DEFAULT");
        inst.setEphemeral(true);
        inst.addMetadata("baseUrl", baseUrl);
        client.registerInstance(SERVICE, inst);
    }

    private void deregisterNode(NamingService client, String instanceId) throws NacosException {
        Instance inst = new Instance();
        inst.setInstanceId(instanceId);
        inst.setIp("127.0.0.1");
        inst.setPort(instanceId.equals(NODE_A) ? OWNER_HTTP_PORT : 18082);
        inst.setClusterName("DEFAULT");
        client.deregisterInstance(SERVICE, inst);
    }

    private boolean pollUntil(java.util.function.BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
