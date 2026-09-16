package io.yunxi.platform.trace.projection;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 投影上下文：投影实现所需的环境信息，以及为有状态投影预留的状态槽。
 *
 * <p><b>当前状态：有状态能力与反向通道<b>只留口、未实现</b>。</b>本类已提供
 * {@link #state()} 与 {@link #inbound()} 两个入口，但平台上没有任何投影使用它们；
 * 这是刻意的 —— 已确认有协议需要它们（全量快照算差量补丁需要跨事件记忆、
 * 断点恢复需要接收客户端回传），但该协议的实现尚未进入排期。现在把口留住，
 * 是为了避免未来改 SPI 签名时波及全部既有实现；这也意味着<b>两个入口在当前版本属未验证路径</b>，
 * 使用前应先确认其形状是否匹配真实需求。</p>
 *
 * <p>投影上下文是<b>单次投影</b>的作用域：一次 project 调用对应一个实例。
 * 进程级共享状态不放这里。</p>
 *
 * @author yunxi-agent-platform
 */
public class ProjectionContext {

    private final String traceId;
    private final String userId;
    private final String sessionId;
    private final Map<String, Object> options;

    /**
     * 跨事件状态槽。预留给需要记忆的投影（如差量补丁需保留上一份全量快照）。
     *
     * <p>并发安全，但<b>语义上属于单次投影</b>：不同投影实例不共享。</p>
     */
    private final Map<String, Object> state = new ConcurrentHashMap<>();

    /**
     * 反向通道入参。预留给需要接收客户端回传的协议（如断点恢复时携带的续传参数）。
     *
     * <p>构造后不可变。当前平台上没有生产者向它写入，恒为空集合。</p>
     */
    private final Map<String, Object> inbound;

    private ProjectionContext(String traceId, String userId, String sessionId,
                              Map<String, Object> options, Map<String, Object> inbound) {
        this.traceId = traceId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.options = options == null ? Map.of() : Map.copyOf(options);
        this.inbound = inbound == null ? Map.of() : Map.copyOf(inbound);
    }

    /**
     * 构造投影上下文。
     *
     * @param traceId   轨迹标识
     * @param userId    用户标识
     * @param sessionId 会话标识
     * @param options   协议选项（如是否携带调试字段）
     * @return 投影上下文
     */
    public static ProjectionContext of(String traceId, String userId, String sessionId,
                                       Map<String, Object> options) {
        return new ProjectionContext(traceId, userId, sessionId, options, Map.of());
    }

    /**
     * 构造带反向入参的投影上下文。
     *
     * <p><b>预留入口，当前无调用方。</b>供需要客户端回传的协议在实现时使用。</p>
     *
     * @param traceId   轨迹标识
     * @param userId    用户标识
     * @param sessionId 会话标识
     * @param options   协议选项
     * @param inbound   反向通道入参，构造后不可变
     * @return 投影上下文
     */
    public static ProjectionContext of(String traceId, String userId, String sessionId,
                                       Map<String, Object> options, Map<String, Object> inbound) {
        return new ProjectionContext(traceId, userId, sessionId, options, inbound);
    }

    public String traceId() {
        return traceId;
    }

    public String userId() {
        return userId;
    }

    public String sessionId() {
        return sessionId;
    }

    /**
     * 协议选项，只读。
     *
     * @return 选项快照
     */
    public Map<String, Object> options() {
        return options;
    }

    /**
     * 跨事件状态槽（可写）。预留给有状态投影，当前无使用者。
     *
     * @return 可变状态槽
     */
    public Map<String, Object> state() {
        return state;
    }

    /**
     * 反向通道入参，只读。预留给客户端回传，当前恒为空。
     *
     * @return 入参快照
     */
    public Map<String, Object> inbound() {
        return Collections.unmodifiableMap(inbound);
    }
}
