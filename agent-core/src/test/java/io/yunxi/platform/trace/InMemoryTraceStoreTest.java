package io.yunxi.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.message.ToolResultState;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * InMemoryTraceStore：写入、读取、订阅与容量约束测试。
 */
@DisplayName("InMemoryTraceStore 轨迹存储")
class InMemoryTraceStoreTest {

    private static final String TRACE = "trace-1";

    private final InMemoryTraceStore store = new InMemoryTraceStore();

    private static ReasoningSpan open(String key) {
        return ReasoningSpan.open(TRACE, key, "e-" + key, SpanKind.TEXT, null);
    }

    @Test
    @DisplayName("按写入顺序读取同一轨迹的快照")
    void readInOrder() {
        store.append(open("a"));
        store.append(ReasoningSpan.delta(TRACE, "a", "e-a", SpanKind.TEXT, "增量"));
        store.append(ReasoningSpan.closed(TRACE, "a", "e-a", SpanKind.TEXT,
                ToolResultState.SUCCESS, 5L, null));

        List<ReasoningSpan> spans = store.read(TRACE);

        assertThat(spans).hasSize(3);
        assertThat(spans.get(0).snapshot()).isEqualTo(Snapshot.OPEN);
        assertThat(spans.get(1).snapshot()).isEqualTo(Snapshot.DELTA);
        assertThat(spans.get(2).snapshot()).isEqualTo(Snapshot.CLOSED);
    }

    @Test
    @DisplayName("轨迹之间互不干扰")
    void tracesAreIsolated() {
        store.append(open("a"));
        store.append(ReasoningSpan.open("trace-2", "b", "e-b", SpanKind.TEXT, null));

        assertThat(store.read(TRACE)).hasSize(1);
        assertThat(store.read("trace-2")).hasSize(1);
        assertThat(store.read("trace-2").get(0).stableKey()).isEqualTo("b");
    }

    @Test
    @DisplayName("未知轨迹返回空列表")
    void unknownTraceReturnsEmpty() {
        assertThat(store.read("missing")).isEmpty();
        assertThat(store.read(null)).isEmpty();
    }

    @Test
    @DisplayName("订阅先回放历史快照，再接收新快照")
    void streamReplaysHistoryThenEmits() {
        store.append(open("a"));
        store.append(ReasoningSpan.closed(TRACE, "a", "e-a", SpanKind.TEXT,
                ToolResultState.SUCCESS, 5L, null));

        Flux<ReasoningSpan> stream = store.stream(TRACE);

        StepVerifier.create(stream)
                .assertNext(s -> assertThat(s.snapshot()).isEqualTo(Snapshot.OPEN))
                .assertNext(s -> assertThat(s.snapshot()).isEqualTo(Snapshot.CLOSED))
                .then(() -> store.append(
                        ReasoningSpan.open(TRACE, "b", "e-b", SpanKind.TOOL_CALL, null)))
                .assertNext(s -> assertThat(s.stableKey()).isEqualTo("b"))
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("单轨迹超出上限时丢弃最早的快照")
    void capacityDropsOldest() {
        InMemoryTraceStore bounded = new InMemoryTraceStore(3);

        for (int i = 0; i < 5; i++) {
            bounded.append(ReasoningSpan.open(TRACE, "k" + i, "e" + i, SpanKind.TEXT, null));
        }

        List<ReasoningSpan> spans = bounded.read(TRACE);
        assertThat(spans).hasSize(3);
        assertThat(spans.get(0).stableKey()).isEqualTo("k2");
        assertThat(spans.get(2).stableKey()).isEqualTo("k4");
    }

    @Test
    @DisplayName("清理后轨迹与订阅通道均释放")
    void clearRemovesTrace() {
        store.append(open("a"));
        store.clear(TRACE);

        assertThat(store.read(TRACE)).isEmpty();
    }

    @Test
    @DisplayName("写入空快照或无轨迹标识的快照不生效，且不抛异常")
    void invalidAppendIsIgnored() {
        assertThatCode(() -> {
            store.append(null);
            store.append(ReasoningSpan.open(null, "k", "e", SpanKind.TEXT, null));
        }).doesNotThrowAnyException();

        assertThat(store.read(null)).isEmpty();
    }

    @Test
    @DisplayName("写入失败被隔离，不影响调用方")
    void appendFailureIsolated() {
        assertThatCode(() -> store.append(open("a"))).doesNotThrowAnyException();
        assertThat(store.read(TRACE)).hasSize(1);
    }

    @Test
    @DisplayName("同一轨迹名重复订阅各自获得全量历史")
    void multipleSubscribersEachGetHistory() {
        store.append(open("a"));

        StepVerifier.create(store.stream(TRACE))
                .assertNext(s -> assertThat(s.stableKey()).isEqualTo("a"))
                .thenCancel()
                .verify();

        StepVerifier.create(store.stream(TRACE))
                .assertNext(s -> assertThat(s.stableKey()).isEqualTo("a"))
                .thenCancel()
                .verify();
    }
}
