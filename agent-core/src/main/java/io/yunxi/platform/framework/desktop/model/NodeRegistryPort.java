package io.yunxi.platform.framework.desktop.model;

/**
 * 节点注册同步端口
 *
 * <p>
 * agent-core（WebSocket 传输层）通过此接口将节点注册/断开/心跳
 * 同步通知到 agent-business（DevOps API 层），替代原先的事件驱动方式。
 * </p>
 *
 * <h3>解决的问题</h3>
 * <ul>
 *   <li>DesktopRelayHandler 和 NodeRegistryService 两套独立注册表的数据一致性问题</li>
 *   <li>事件驱动异步同步的时序间隙问题（注册事件可能在查询之后才处理）</li>
 * </ul>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>同步调用：注册/注销/心跳在 WebSocket 处理线程内同步完成，无延迟</li>
 *   <li>可选依赖：{@code @Autowired(required = false)}，agent-business 未加载时自动跳过</li>
 *   <li>接口隔离：agent-core 不依赖 agent-business 的具体实现类</li>
 * </ul>
 *
 */
public interface NodeRegistryPort {

    /**
     * 节点注册通知
     *
     * @param info      节点信息（来自 WebSocket 注册消息）
     * @param sessionId WebSocket 会话 ID
     */
    void onNodeRegistered(NodeInfo info, String sessionId);

    /**
     * 节点断开通知
     *
     * @param clientId 断开的客户端 ID
     */
    void onNodeDisconnected(String clientId);

    /**
     * 节点心跳通知
     *
     * @param clientId 心跳的客户端 ID
     */
    void onNodeHeartbeat(String clientId);
}