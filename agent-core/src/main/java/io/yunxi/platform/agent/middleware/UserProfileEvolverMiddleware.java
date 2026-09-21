package io.yunxi.platform.agent.middleware;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.platform.profile.ProfileEvolutionThrottle;
import io.yunxi.platform.profile.RedisUserProfileStore;
import io.yunxi.platform.spi.UserProfileEvolver;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;
import reactor.core.publisher.Flux;

/**
 * 画像演进中间件：在调用收尾阶段（{@code onAgent}）异步从本轮对话抽取身份信号并合并画像。
 *
 * <p>与 {@link UserProfileMiddleware} 构成「读 / 写」对称的一对：读侧在
 * {@code onSystemPrompt} 把已存画像注入提示词，写侧在本轮收尾把新信号落库，
 * 于是下一轮开始即可读到更新后的画像，时序恰好闭合。两者同属运行时内的横切关切，
 * 因此都用 AgentScope-Java 原生 {@link MiddlewareBase} 实现，不在业务服务里另起一条路径。</p>
 *
 * <p><b>不阻塞主调用</b>：抽取在 {@code doOnComplete} 之后提交到独立守护线程池，
 * 用户响应不等待抽取结果。同一 userId 的抽取串行执行，避免「读-改-写」并发丢更新
 * （{@link RedisUserProfileStore} 是 get → evolve → save 语义）。抽取失败在
 * {@link UserProfileEvolver} 内部已由 TimeLimiter + CircuitBreaker 兜底为「不更新」，
 * 本中间件再兜一层异常，绝不向事件流抛错。</p>
 *
 * <p><b>节流</b>：是否真的触发由 {@link ProfileEvolutionThrottle} 决定（时间 / 轮次或关系），
 * 避免每轮都抽导致 token 成本随轮数线性增长。</p>
 *
 * @author yunxi-agent-platform
 */
public class UserProfileEvolverMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(UserProfileEvolverMiddleware.class);

    /** 送入抽取的助手回复文本长度上限，避免把超长回答整段灌进抽取窗口。 */
    private static final int MAX_ASSISTANT_CHARS = 2000;

    private final RedisUserProfileStore store;
    private final UserProfileEvolver evolver;
    private final ProfileEvolutionThrottle throttle;

    private final ExecutorService executor;
    /** 按 userId 串行化抽取，防止并发读-改-写丢更新。 */
    private final Map<String, Object> userLocks = new ConcurrentHashMap<>();

    public UserProfileEvolverMiddleware(RedisUserProfileStore store, UserProfileEvolver evolver,
            ProfileEvolutionThrottle throttle) {
        this.store = store;
        this.evolver = evolver;
        this.throttle = throttle;
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "profile-evolve-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(2, factory);
    }

    @Override
    public int order() {
        // 收尾抽取在审计之后执行，确保审计耗时统计不被抽取影响
        return 130;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        String userId = ctx != null ? ctx.getUserId() : null;
        String userText = firstUserText(input);
        if (userId == null || userId.isBlank() || userText == null) {
            return next.apply(input);
        }

        List<String> assistantParts = new ArrayList<>();
        return next.apply(input)
                .doOnNext(event -> collectAssistant(event, assistantParts))
                .doOnComplete(() -> dispatch(userId, userText, assistantParts));
    }

    /**
     * 提交一次收尾抽取。节流未放行则直接跳过；提交后立即返回，不阻塞事件流收尾。
     */
    private void dispatch(String userId, String userText, List<String> assistantParts) {
        try {
            if (!throttle.tryAcquire(userId)) {
                return;
            }
            List<Msg> turns = buildTurns(userText, assistantParts);
            if (turns.isEmpty()) {
                return;
            }
            executor.submit(() -> runEvolve(userId, turns));
        } catch (Exception e) {
            // 提交阶段异常同样不得影响主调用
            log.warn("[PROFILE] 画像抽取提交失败: userId={}: {}", userId, e.getMessage());
        }
    }

    private void runEvolve(String userId, List<Msg> turns) {
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            try {
                UserProfile current = store.getProfile(userId);
                if (current == null) {
                    current = new UserProfile(userId, List.of(), Map.of(), Map.of(), System.currentTimeMillis());
                }
                UserProfile updated = evolver.evolve(current, turns);
                if (updated != null && updated != current) {
                    store.saveProfile(userId, updated);
                }
            } catch (Exception e) {
                log.warn("[PROFILE] 画像异步抽取失败: userId={}: {}", userId, e.getMessage());
            } finally {
                userLocks.remove(userId);
            }
        }
    }

    /**
     * 组装本轮「用户提问 + 助手回答」为一次抽取输入。
     *
     * <p>助手回复从事件流累积（{@code AgentResultEvent.result}），多数场景已覆盖最终答复。</p>
     */
    private List<Msg> buildTurns(String userText, List<String> assistantParts) {
        List<Msg> turns = new ArrayList<>(2);
        turns.add(Msg.builder().role(MsgRole.USER).textContent(userText).build());
        String assistant = joinAssistant(assistantParts);
        if (assistant != null && !assistant.isBlank()) {
            turns.add(Msg.builder().role(MsgRole.ASSISTANT).textContent(assistant).build());
        }
        return turns;
    }

    private static String joinAssistant(List<String> parts) {
        if (parts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                sb.append(p);
            }
        }
        if (sb.length() > MAX_ASSISTANT_CHARS) {
            return sb.substring(0, MAX_ASSISTANT_CHARS);
        }
        return sb.toString();
    }

    /** 从事件流收集助手最终答复文本（{@link AgentResultEvent} 承载本轮结果）。 */
    private static void collectAssistant(AgentEvent event, List<String> sink) {
        if (!(event instanceof AgentResultEvent result) || result.getResult() == null) {
            return;
        }
        String text = result.getResult().getTextContent();
        if (text != null && !text.isBlank()) {
            sink.add(text);
        }
    }

    /** 取本轮输入中的第一条非空用户文本作为抽取的对话起点。 */
    private static String firstUserText(AgentInput input) {
        if (input == null || input.msgs() == null) {
            return null;
        }
        for (Msg msg : input.msgs()) {
            if (msg != null && msg.getRole() == MsgRole.USER) {
                String text = msg.getTextContent();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }
}
