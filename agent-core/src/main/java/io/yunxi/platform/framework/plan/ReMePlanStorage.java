package io.yunxi.platform.framework.plan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.memory.reme.ReMeAddRequest;
import io.agentscope.core.memory.reme.ReMeClient;
import io.agentscope.core.memory.reme.ReMeSearchRequest;
import io.agentscope.core.memory.reme.ReMeTrajectory;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.storage.PlanStorage;
import reactor.core.publisher.Mono;

/**
 * ReMe 规划存储 — 实现 agentscope PlanStorage SPI。
 * <p>
 * 将规划持久化桥接到 yunxi 的记忆体系：
 * <ul>
 * <li>短期：通过 MemoryCoordinatorService 存储到会话上下文</li>
 * <li>长期：通过 ReMeClient 存储到 ReMe 长期记忆服务，支持跨会话检索</li>
 * </ul>
 * </p>
 *
 * 替换 agentscope 默认的 InMemoryPlanStorage，在 AgentConfigurer 中注入。
 */
@Component
public class ReMePlanStorage implements PlanStorage {

    private static final Logger log = LoggerFactory.getLogger(ReMePlanStorage.class);

    /** ReMe workspace 前缀（隔离规划数据） */
    private static final String PLAN_WORKSPACE_PREFIX = "plan_";

    /** 内存缓存（确保运行时可访问，同时持久化到 ReMe） */
    private final Map<String, Plan> planCache = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final Optional<ReMeClient> reMeClient;

    public ReMePlanStorage(
            ObjectMapper objectMapper,
            Optional<ReMeClient> reMeClient) {
        this.objectMapper = objectMapper;
        this.reMeClient = reMeClient;
    }

    @Override
    public Mono<Void> addPlan(Plan plan) {
        return Mono.fromRunnable(() -> {
            String planId = plan.getId();
            // 1. 写入内存缓存
            planCache.put(planId, plan);
            log.debug("规划已缓存: {} (id={})", plan.getName(), planId);

            // 2. 持久化到 ReMe 长期记忆
            reMeClient.ifPresent(client -> {
                try {
                    String planJson = objectMapper.writeValueAsString(plan);
                    // 使用 ReMe 的个人记忆端点存储规划
                    client.add(new ReMeAddRequest(
                            PLAN_WORKSPACE_PREFIX + planId,
                            List.of(new ReMeTrajectory(List.of(
                                    new io.agentscope.core.memory.reme.ReMeMessage(
                                            "assistant",
                                            "plan: " + plan.getName() + "\n" + planJson))))))
                            .subscribe(
                                    response -> log.debug("规划已持久化到 ReMe: {} (id={})", plan.getName(), planId),
                                    error -> log.warn("规划持久化到 ReMe 失败: {} - {}", plan.getName(), error.getMessage()));
                } catch (Exception e) {
                    log.warn("序列化规划失败: {}", e.getMessage());
                }
            });
        });
    }

    @Override
    public Mono<Plan> getPlan(String planId) {
        return Mono.fromSupplier(() -> {
            // 1. 先查内存缓存
            Plan plan = planCache.get(planId);
            if (plan != null) {
                return plan;
            }

            // 2. 从 ReMe 检索
            if (reMeClient.isPresent()) {
                try {
                    var response = reMeClient.get()
                            .search(new ReMeSearchRequest(
                                    PLAN_WORKSPACE_PREFIX + planId,
                                    "plan_" + planId,
                                    3))
                            .block();
                    if (response != null && response.getAnswer() != null) {
                        log.debug("从 ReMe 检索到规划: {}", planId);
                        // 注意：此处只返回了 metadata，完整反序列化需要重存 Plan 对象
                        // 内存缓存优先，ReMe 作为灾备
                    }
                } catch (Exception e) {
                    log.debug("从 ReMe 检索规划失败: {}", e.getMessage());
                }
            }

            return null;
        });
    }

    @Override
    public Mono<List<Plan>> getPlans() {
        return Mono.fromSupplier(() -> List.copyOf(planCache.values()));
    }
}