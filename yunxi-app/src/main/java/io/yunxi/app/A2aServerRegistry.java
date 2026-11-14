package io.yunxi.app;

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 进程内 A2A server 索引，供 A2aController 按 agentId 路由 JSON-RPC 请求。
 *
 * <p>每个已注册的 yunxi Agent 对应一个由框架 AgentScopeA2aServer 构建的实例；A2A 协议语义完全由框架承担。</p>
 *
 * <p>支持两种注册（与框架 {@code AguiAgentRegistry.registerFactory} 同源思路，此处 yunxi 自管）：
 * <ul>
 *   <li>{@link #register(String, AgentScopeA2aServer)}：eager 单例（注册即构建并标记就绪）。</li>
 *   <li>{@link #registerFactory(String, Supplier)}：惰性工厂，首个 {@link #get(String)} 才构建并缓存（单节点单实例），避免启动期全量拉起 A2A server。</li>
 * </ul>
 * {@link #get(String)} 优先返回已就绪实例；缺失时回退工厂惰性构建并缓存，保证单节点单实例（不每次重建）。
 */
@Component
public class A2aServerRegistry {

    private final Map<String, AgentScopeA2aServer> servers = new ConcurrentHashMap<>();
    private final Map<String, Supplier<AgentScopeA2aServer>> factories = new ConcurrentHashMap<>();

    /** 按 agentId 注册一个 A2A server 实例（覆盖同名，eager）。 */
    public void register(String agentId, AgentScopeA2aServer server) {
        servers.put(agentId, server);
    }

    /** 按 agentId 注册一个惰性工厂：首个 {@link #get(String)} 才构建并缓存（单节点单实例）。 */
    public void registerFactory(String agentId, Supplier<AgentScopeA2aServer> factory) {
        factories.put(agentId, factory);
    }

    /**
     * 按 agentId 取出对应 A2A server；优先已就绪实例，缺失则惰性构建工厂结果并缓存；均未注册返回 {@code null}。
     * 并发下 {@code computeIfAbsent} 保证工厂至多构建一次（单节点单实例）。
     */
    public AgentScopeA2aServer get(String agentId) {
        AgentScopeA2aServer ready = servers.get(agentId);
        if (ready != null) {
            return ready;
        }
        Supplier<AgentScopeA2aServer> factory = factories.get(agentId);
        if (factory == null) {
            return null;
        }
        return servers.computeIfAbsent(agentId, id -> factory.get());
    }
}
