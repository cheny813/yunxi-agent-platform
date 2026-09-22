package io.yunxi.platform.aistio.orchestration;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.shared.codec.CodecSpec;

import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.aistio.AistioIntegrationProperties.TriggerConfig;
import io.yunxi.platform.aistio.AistioIntegrationProperties.TriggerConfig.AutomationBinding;
import io.yunxi.platform.aistio.client.AistioControlPlaneClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 编排触发的唯一实现：automation webhook。
 *
 * <p>端点 {@code POST /hooks/v1/automations/{automationId}/{triggerId}} 注册在无鉴权公共段，
 * 凭 {@code X-Automation-Secret} 校验（sha256 常量时间比对，不符 401），
 * {@code Idempotency-Key} 必填，限流 60 次/分钟（automationId + ClientIP）。
 * 数据面内网 token 在该链路上不可用，故此处<b>不携带</b> internal-token。
 *
 * <p>响应体只有 {@code {"status":..., "delivery":{...}}}，<b>没有 runId</b>：
 * 编排 run 由异步 worker 创建，触发侧无从得知，因此回执不含 orchestrationRunId（§3.3.0）。
 *
 * <p>与服务端交互仅此一处，统一走 {@link AistioControlPlaneClient}（适配层）：控制面地址、鉴权头、超时与重试
 * 集中于一处，webhook secret 与幂等键作为方法参数传入（凭据一律作参数、不外渗产品平面核心）。</p>
 */
public class WebhookOrchestrationTrigger implements OrchestrationTrigger {

    private static final Logger log = LoggerFactory.getLogger(WebhookOrchestrationTrigger.class);

    /** §3.8：触发侧把 correlationId 放在 body 的 _yunxi 段，随 run input 一路下推到 attempt payload */
    private static final String YUNXI_SEGMENT = "_yunxi";

    /**
     * 探测专用常量：一个**恒不存在**的 triggerId，仅用于区分「鉴权失败」与「路由/参数错误」，
     * 绝不用于真实触发（§3.3.4：探测必须无副作用）。
     */
    private static final String PROBE_TRIGGER_ID = "yunxi-probe-nonexistent";

    private final AistioIntegrationProperties properties;
    private final TriggerIdempotencyStore idempotencyStore;
    private final ObjectMapper mapper;
    private final AistioControlPlaneClient client;

    public WebhookOrchestrationTrigger(AistioIntegrationProperties properties,
            TriggerIdempotencyStore idempotencyStore) {
        this.properties = properties;
        this.idempotencyStore = idempotencyStore;
        this.mapper = CodecSpec.storage();
        this.client = new AistioControlPlaneClient(properties.getControlPlaneUrl(), this.mapper);
    }

    public WebhookOrchestrationTrigger(AistioIntegrationProperties properties) {
        this(properties, new TriggerIdempotencyStore.InMemory());
    }

    @Override
    public Mono<TriggerReceipt> start(String definitionKey, Map<String, Object> input, String bizRequestId) {
        return Mono.fromCallable(() -> doStart(definitionKey, input, bizRequestId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private TriggerReceipt doStart(String definitionKey, Map<String, Object> input, String bizRequestId) {
        AutomationBinding binding = requireBinding(definitionKey);
        if (bizRequestId == null || bizRequestId.isBlank()) {
            throw new IllegalArgumentException("bizRequestId is required（同一逻辑请求重试时必须传同一个值）");
        }
        // 幂等键按业务请求 ID 换取：首次生成并记住，重试复用（否则服务端会当成两次触发）
        String idempotencyKey = idempotencyStore.idempotencyKeyFor(bizRequestId);
        String correlationId = UUID.randomUUID().toString();

        Map<String, Object> body = new LinkedHashMap<>(input == null ? Map.of() : input);
        body.put(YUNXI_SEGMENT, Map.of(
                "correlationId", correlationId,
                "definitionKey", definitionKey,
                "bizRequestId", bizRequestId));

        HttpResponse<String> response = post(binding.getAutomationId(), binding.getTriggerId(),
                binding.getSecret(), idempotencyKey, body);
        int status = response.statusCode();
        if (status == 401) {
            throw new IllegalStateException("webhook secret 无效（401）：核对 yunxi.aistio.trigger.automations."
                    + definitionKey + ".secret 与控制台下发值");
        }
        if (status == 404) {
            throw new IllegalStateException("automation 不存在或路径错误（404）：automationId=" + binding.getAutomationId());
        }
        if (status == 429) {
            throw new IllegalStateException("触发限流（429，60 次/分钟按 automationId+IP 共享）：请稍后重试，"
                    + "或按场景拆分 automation 避免配额被单一场景打满");
        }
        if (status != 200) {
            throw new IllegalStateException("webhook 触发失败：HTTP " + status + " body=" + response.body());
        }

        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode delivery = root.path("delivery");
            TriggerReceipt receipt = new TriggerReceipt(
                    delivery.path("id").asText(null),
                    delivery.path("runId").asText(null),
                    root.path("status").asText(null),
                    correlationId);
            log.info("[AISTIO-TRIGGER] 已触发 definitionKey={} status={} delivery={} automationRun={} correlation={}",
                    definitionKey, receipt.status(), receipt.deliveryId(), receipt.automationRunId(), correlationId);
            return receipt;
        } catch (Exception e) {
            throw new IllegalStateException("webhook 响应解析失败: " + response.body(), e);
        }
    }

    @Override
    public OrchestrationCapability capability() {
        TriggerConfig config = properties.getTrigger();
        if (!config.isEnabled() || config.getAutomations() == null || config.getAutomations().isEmpty()) {
            return OrchestrationCapability.unavailable(
                    "编排触发未启用或未配置 automation 绑定（yunxi.aistio.trigger.enabled / automations）",
                    List.of());
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, AutomationBinding> entry : config.getAutomations().entrySet()) {
            String definitionKey = entry.getKey();
            AutomationBinding binding = entry.getValue();
            if (binding == null || isBlank(binding.getAutomationId()) || isBlank(binding.getTriggerId())
                    || isBlank(binding.getSecret())) {
                problems.add(definitionKey + ": automationId / triggerId / secret 必须齐全");
                continue;
            }
            problems.add(definitionKey + ": " + probe(binding));
        }
        boolean failed = problems.stream().anyMatch(p -> !p.contains("可达"));
        return failed
                ? OrchestrationCapability.unavailable("编排触发存在不可用的绑定，详见 problems", problems)
                : OrchestrationCapability.ok("全部绑定探测可达且 secret 校验通过", problems);
    }

    /**
     * 无副作用探测：用恒不存在的 triggerId 打一次，按状态码区分配置错误类型（§3.3.4）。
     *
     * <p>判定：<b>400 是预期结果</b> —— 说明 secret 校验已通过、路由正确，只是 triggerId 不存在
     * （这正是探测构造出来的情形）；401 = secret 错；404 = automation 不存在；
     * 200 = 异常（探测竟然触发了流程）。部分协议表格把 400 也列为 ERROR，
     * 但那与"用不存在的 triggerId 探测"这一前提自相矛盾，故此处按推理而非按表格实现。</p>
     */
    private String probe(AutomationBinding binding) {
        try {
            HttpResponse<String> response = post(binding.getAutomationId(), PROBE_TRIGGER_ID, binding.getSecret(),
                    UUID.randomUUID().toString(), Map.of(YUNXI_SEGMENT, Map.of("probe", true)));
            int status = response.statusCode();
            return switch (status) {
                case 400 -> "入口可达且 secret 校验通过（400 invalid triggerId，探测预期结果）";
                case 401 -> "secret 错误（401）：核对控制台下发的 webhook secret";
                case 404 -> "automation 不存在或路径错误（404）";
                case 429 -> "限流（429）：探测也占用配额，稍后重试";
                case 200 -> "异常：探测返回 200，说明它真实触发了流程，配置须立即核对";
                default -> "未知响应 HTTP " + status;
            };
        } catch (Exception e) {
            return "探测失败: " + e.getMessage();
        }
    }

    private HttpResponse<String> post(String automationId, String triggerId, String secret,
            String idempotencyKey, Map<String, Object> body) {
        return client.triggerAutomation(automationId, triggerId, secret, idempotencyKey, body);
    }

    private AutomationBinding requireBinding(String definitionKey) {
        TriggerConfig config = properties.getTrigger();
        if (!config.isEnabled()) {
            throw new IllegalStateException("编排触发未启用：设置 yunxi.aistio.trigger.enabled=true");
        }
        AutomationBinding binding = config.getAutomations() == null
                ? null
                : config.getAutomations().get(definitionKey);
        if (binding == null) {
            throw new IllegalArgumentException("未找到 definitionKey 的 automation 绑定: " + definitionKey);
        }
        if (isBlank(binding.getAutomationId()) || isBlank(binding.getTriggerId()) || isBlank(binding.getSecret())) {
            throw new IllegalStateException("绑定不完整（automationId / triggerId / secret 必填）: " + definitionKey);
        }
        return binding;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
