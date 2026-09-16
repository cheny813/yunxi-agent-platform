package io.yunxi.platform.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.yunxi.platform.trace.ReasoningSpan;
import io.yunxi.platform.trace.TraceStore;
import io.yunxi.platform.trace.projection.ProjectionContext;
import io.yunxi.platform.trace.projection.AguiProjection;
import io.yunxi.platform.trace.projection.SseProjection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 推理轨迹查询接口。
 *
 * <p>轨迹是产品侧资产，面向界面回放、离线评估与审计追溯。存储的写入发生在执行过程中
 * （由统一执行引擎在内容流装配阶段旁路落库，见 {@code AgentExecutionEngine}），
 * 此控制器只负责读取、回放与清理。</p>
 *
 * <p>轨迹标识的构造规则：调用方未显式指定时，由编排层按
 * {@code userId:sessionId} 生成，与执行期归集所用的键一致（见归集中间件的标识解析）。
 * 单实例部署使用内存实现；多实例部署应替换为共享存储实现，此时本接口语义不变。</p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceStore traceStore;
    private final SseProjection sseProjection;
    private final AguiProjection aguiProjection;

    public TraceController(TraceStore traceStore, SseProjection sseProjection, AguiProjection aguiProjection) {
        this.traceStore = traceStore;
        this.sseProjection = sseProjection;
        this.aguiProjection = aguiProjection;
    }

    /**
     * 按会话取轨迹标识。
     *
     * <p>标识规则与执行期保持一致，便于前端用会话信息直接定位轨迹。</p>
     */
    private static String traceIdOf(String userId, String sessionId) {
        if (userId == null || userId.isBlank()) {
            return sessionId;
        }
        return userId + ":" + sessionId;
    }

    /**
     * 读取一条轨迹的全部快照。
     *
     * <p>快照按产生顺序返回，可直接用于构建轨迹树：{@code parentKey} 指向父节点，
     * {@code stableKey} 是节点标识，同一节点的多次快照（开启 / 增量 / 关闭）共享该标识。</p>
     *
     * @param userId    用户标识（与会话一起定位轨迹）
     * @param sessionId 会话标识
     * @return 轨迹快照列表与元信息
     */
    @GetMapping
    public Map<String, Object> read(@RequestParam(required = false) String userId,
                                    @RequestParam String sessionId) {
        String traceId = traceIdOf(userId, sessionId);
        List<ReasoningSpan> spans = traceStore.read(traceId);
        log.info("查询轨迹: traceId={}, spans={}", traceId, spans.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("spanCount", spans.size());
        result.put("spans", spans);
        return result;
    }

    /**
     * 以流的形式订阅轨迹：先回放已产生的快照，再持续接收新快照。
     *
     * <p>用于界面实时渲染与长连接回放。流在客户端断开时结束。</p>
     *
     * @param userId    用户标识
     * @param sessionId 会话标识
     * @return 快照流（服务端推送）
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ReasoningSpan> stream(@RequestParam(required = false) String userId,
                                      @RequestParam String sessionId) {
        String traceId = traceIdOf(userId, sessionId);
        log.info("订阅轨迹: traceId={}", traceId);
        return traceStore.stream(traceId);
    }

    /**
     * 清理一条轨迹。
     *
     * <p>轨迹属观测数据，清理不影响会话内容与执行状态。</p>
     *
     * @param userId    用户标识
     * @param sessionId 会话标识
     * @return 操作结果
     */
    @DeleteMapping
    public Map<String, Object> clear(@RequestParam(required = false) String userId,
                                     @RequestParam String sessionId) {
        String traceId = traceIdOf(userId, sessionId);
        traceStore.clear(traceId);
        log.info("清理轨迹: traceId={}", traceId);
        return Map.of("success", true, "traceId", traceId);
    }

    /**
     * 按显式轨迹标识读取（供已注入自定义标识的调用方使用）。
     *
     * @param traceId 轨迹标识
     * @return 轨迹快照列表与元信息
     */
    @GetMapping("/{traceId}")
    public Map<String, Object> readByTraceId(@PathVariable String traceId) {
        List<ReasoningSpan> spans = traceStore.read(traceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("spanCount", spans.size());
        result.put("spans", spans);
        return result;
    }

    /**
     * 把一条已落库轨迹投影为 SSE 消息流回放（归集结果直接供投影消费）。
     *
     * <p>存储的语义快照流经 SSE 协议投影，还原为前端可直接消费的消息序列；
     * 与实时输出共用同一投影实现，保证回放与实时一致。</p>
     *
     * @param traceId 轨迹标识（执行期按 userId:sessionId 生成）
     * @return SSE 消息流
     */
    @GetMapping(value = "/{traceId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamProjected(@PathVariable String traceId) {
        List<ReasoningSpan> spans = traceStore.read(traceId);
        ProjectionContext pctx = ProjectionContext.of(traceId, null, null, Map.of());
        return sseProjection.project(Flux.fromIterable(spans), pctx)
                .map(msg -> ServerSentEvent.<String>builder(msg).build());
    }

    /**
     * 把一条已落库轨迹投影为 AG-UI 协议事件流回放（M5）。
     *
     * <p>与 SSE 投影共用同一落库快照，但输出 AG-UI 事件 JSON（{@code data} 字段为事件体，
     * 消费者按 {@code type} 分发），供 AG-UI 兼容前端直接消费。</p>
     *
     * @param traceId 轨迹标识（执行期按 userId:sessionId 生成）
     * @return AG-UI 事件流（服务端推送）
     */
    @GetMapping(value = "/{traceId}/ag-ui", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamAgui(@PathVariable String traceId) {
        List<ReasoningSpan> spans = traceStore.read(traceId);
        ProjectionContext pctx = ProjectionContext.of(traceId, null, null, Map.of());
        return aguiProjection.project(Flux.fromIterable(spans), pctx)
                .map(msg -> ServerSentEvent.<String>builder(msg).build());
    }
}
