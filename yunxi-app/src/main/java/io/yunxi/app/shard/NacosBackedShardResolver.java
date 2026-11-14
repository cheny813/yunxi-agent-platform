package io.yunxi.app.shard;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import io.yunxi.fabric.CrossInstanceEvent;
import io.yunxi.fabric.CrossInstanceEventBus;
import io.yunxi.fabric.CrossInstanceEventType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 基于 Nacos 服务发现的一致性哈希环分片解析器。
 *
 * <p>实例集经 {@link NamingService} 发现（N = 发现的实例数），构建一致性哈希环：每物理实例映射
 * {@code virtualNodes} 个虚拟节点（{@code key#i}），agentId 经 MD5 取 long 落到环上最近虚拟节点即归属该实例。
 * 实例集经 {@code subscribe} 增量刷新本地 volatile 环，实例增删仅迁移约 1/N 的归属（取模会全局重排，禁用）。</p>
 *
 * <p>本实例身份经 {@link InstanceIdentity} 获取（与 aistio fallback 同款的 {@code app-host-port}）。
 * Nacos 不可达 / 实例列表为空时 fail-open：环退化为仅本实例（{@code owns} 永真），依赖 StartupGate 防并发风暴。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.shard.enabled", havingValue = "true")
public class NacosBackedShardResolver implements AgentShardResolver {

    private final NamingService namingService;
    private final InstanceIdentity instanceIdentity;
    private final String serviceName;
    private final int virtualNodes;
    private volatile NavigableMap<Long, String> ring = new TreeMap<>();

    /**
     * 跨实例业务事件总线（{@code yunxi.cluster.mode=cluster} 时由 RuntimeConfiguration 装配的
     * {@code CrossInstanceEventBus} bean；standalone 或总线 bean 缺失时为 null，本类发布 / 订阅均安全跳过，
     * 分片环仍由 Nacos 订阅兜底（fail-open）。
     */
    @Autowired
    private ObjectProvider<CrossInstanceEventBus> eventBusProvider;

    public NacosBackedShardResolver(NamingService namingService,
                                    InstanceIdentity instanceIdentity,
                                    @Value("${yunxi.shard.service-name:yunxi-runtime}") String serviceName,
                                    @Value("${yunxi.shard.virtual-nodes:160}") int virtualNodes) {
        this.namingService = namingService;
        this.instanceIdentity = instanceIdentity;
        this.serviceName = serviceName;
        this.virtualNodes = virtualNodes;
        try {
            rebuildRing(namingService.getAllInstances(serviceName));
            namingService.subscribe(serviceName, event -> {
                try {
                    rebuildRing(namingService.getAllInstances(serviceName));
                } catch (Exception ignored) {
                    // 保留旧环，不中断
                }
                // 跨实例即时扇出：通知其它实例主动刷新分片环（增强 Nacos 推送延迟）。
                CrossInstanceEventBus bus =
                        eventBusProvider != null ? eventBusProvider.getIfAvailable() : null;
                if (bus != null) {
                    bus.publish(CrossInstanceEvent.of(
                            CrossInstanceEventType.SHARD_REBALANCE, Map.of(), instanceIdentity.instanceKey()));
                }
            });
        } catch (Exception e) {
            ring = singletonRing(instanceIdentity.instanceKey());
        }
    }

    /**
     * 订阅跨实例分片重平衡事件。仅在集群模式（事件总线 bean 存在）下生效；接收到 peer 广播（自身广播已跳过）
     * 后主动从 Nacos 重算分片环，避免等待本实例 Nacos 推送延迟。重算幂等，失败保留旧环。
     */
    @PostConstruct
    public void initEventSubscription() {
        CrossInstanceEventBus bus = eventBusProvider != null ? eventBusProvider.getIfAvailable() : null;
        if (bus == null) {
            return;
        }
        bus.subscribe(CrossInstanceEventType.SHARD_REBALANCE, event -> {
            if (instanceIdentity != null
                    && instanceIdentity.instanceKey().equals(event.getSourceInstanceKey())) {
                return; // 忽略自身广播
            }
            refreshRingFromNacos();
        });
    }

    private void refreshRingFromNacos() {
        try {
            rebuildRing(namingService.getAllInstances(serviceName));
        } catch (Exception ignored) {
            // 保留旧环，不中断
        }
    }

    private void rebuildRing(List<Instance> instances) {
        NavigableMap<Long, String> newRing = new TreeMap<>();
        if (instances == null || instances.isEmpty()) {
            for (int i = 0; i < virtualNodes; i++) {
                newRing.put(hash(instanceIdentity.instanceKey() + "#" + i), instanceIdentity.instanceKey());
            }
        } else {
            for (Instance inst : instances) {
                String key = inst.getInstanceId();
                for (int i = 0; i < virtualNodes; i++) {
                    newRing.put(hash(key + "#" + i), key);
                }
            }
        }
        ring = newRing;
    }

    private NavigableMap<Long, String> singletonRing(String key) {
        NavigableMap<Long, String> m = new TreeMap<>();
        for (int i = 0; i < virtualNodes; i++) {
            m.put(hash(key + "#" + i), key);
        }
        return m;
    }

    @Override
    public boolean owns(String agentId) {
        String owner = resolveOwnerInstanceKey(agentId);
        return owner != null && owner.equals(instanceIdentity.instanceKey());
    }

    @Override
    public String resolveOwnerInstanceKey(String agentId) {
        NavigableMap<Long, String> r = ring;
        if (r.isEmpty()) {
            return null;
        }
        long h = hash(agentId);
        NavigableMap.Entry<Long, String> entry = r.ceilingEntry(h);
        if (entry == null) {
            entry = r.firstEntry();
        }
        return entry != null ? entry.getValue() : null;
    }

    private long hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (b[i] & 0xFF);
            }
            return h;
        } catch (Exception e) {
            throw new IllegalStateException("md5 hash failed", e);
        }
    }
}
