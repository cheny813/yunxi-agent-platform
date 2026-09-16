package io.yunxi.platform.trace.projection;

import io.yunxi.platform.trace.ReasoningSpan;
import reactor.core.publisher.Flux;

/**
 * 轨迹投影 SPI：把一条推理轨迹投影为某个协议的输出表示。
 *
 * <p>内核只维护一种语义（{@link ReasoningSpan} 快照流），SSE / AG-UI / A2A / OTel 都是它的
 * 投影。新增一个协议等于新增一个实现类，不改内核。</p>
 *
 * <p><b>为什么投影是无状态的、以及为何仍要留口</b>：投影当前被定义为<b>无状态流变换</b> ——
 * 同样的输入跨度序列产生同样的输出，不依赖跨调用的记忆。这个约束让投影可并行、可重放、
 * 可测试。但已确认有一类协议能力需要跨事件状态与客户端回传：全量快照算差量补丁
 * （RFC 6902 风格的 delta）必须记住上一次快照，断点恢复也必须能接收客户端的回传参数。
 * 因此 SPI 在此处<b>预留</b> {@link ProjectionContext} 的状态存取与反向入参位，
 * <b>本阶段只留口、不实现</b> —— 待该协议进入实现时再填，避免现在猜一个错的形状。</p>
 *
 * <p><b>投影不得携带推理语义</b>：投影层只做「同样的语义、换一种表示」，
 * 不允许在投影里做决策、过滤业务规则或改写轨迹。任何需要判断的逻辑都属于归集层。</p>
 *
 * @param <OUT> 输出类型（SSE 为字符串，AG-UI 为协议事件对象）
 * @author yunxi-agent-platform
 */
public interface TraceProjection<OUT> {

    /**
     * 投影名称，用于按协议选择实现与日志归类。
     *
     * @return 协议名，如 {@code sse} / {@code ag-ui} / {@code a2a} / {@code otel}
     */
    String name();

    /**
     * 把快照流投影为协议输出流。
     *
     * <p>实现应当是<b>惰性</b>的：投影在订阅时才发生，不预先把整条轨迹读入内存。
     * 输入流可能持续很长时间（长回合），输出应当与输入同步推进。</p>
     *
     * @param spans 轨迹快照流，按产生顺序到达
     * @param ctx   投影上下文（提供会话身份、协议选项，以及为有状态投影预留的状态存取）
     * @return 协议输出流
     */
    Flux<OUT> project(Flux<ReasoningSpan> spans, ProjectionContext ctx);
}
