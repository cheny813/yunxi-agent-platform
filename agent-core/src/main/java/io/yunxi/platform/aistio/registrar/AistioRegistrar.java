package io.yunxi.platform.aistio.registrar;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.aistio.dto.AgentRegistrationRequest;
import io.yunxi.platform.aistio.dto.AgentRegistrationResponse;
import io.yunxi.platform.aistio.dto.DataPlaneInfo;
import io.yunxi.platform.aistio.service.AistioInfoService;
import lombok.RequiredArgsConstructor;

/**
 * aistio 数据面注册器（对齐新版 Managed Agents 控制面）。
 *
 * <p>应用上下文就绪后，向 aistio 控制面 {@code POST /api/v1/agent-registrations} 注册本数据面。
 * 该端点一次调用即创建 agent + binding + instance，并把 {@code routingKey}（本数据面的 /agentscope 契约基址）
 * 写入控制面注册表，使其能通过 HTTP 轮询本数据面（/agentscope/info、/agentscope/sessions 等）。
 * 注册在后台线程执行，失败仅 WARN 并有限重试，<b>绝不阻塞启动</b>。</p>
 *
 * <p>新版端点采用「数据面自持机器身份信任边界」模型：注册本身<b>不需要</b> X-Builder-Internal-Token
 * （该中间件仅保护 /api/internal/runtime-sessions）；控制面在响应中回传 registrationCredential，
 * 供后续可选的 gRPC ASDP 连接器（Phase 3）做长连接鉴权。存活判定由控制面 prober 轮询 /agentscope/health 完成，
 * 数据面无需主动心跳。</p>
 */
@Component
@ConditionalOnProperty(prefix = "yunxi.aistio", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AistioRegistrar {

    private static final Logger log = LoggerFactory.getLogger(AistioRegistrar.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 10_000L;

    private final AistioIntegrationProperties properties;
    private final AistioInfoService infoService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 本次进程唯一的实例标识（每次启动生成，重启即新实例） */
    private String instanceKey;

    /** 注册成功后由控制面回传的凭证（预留给 Phase 3 gRPC ASDP 连接器） */
    private volatile String registrationCredential;

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getControlPlaneUrl() == null || properties.getControlPlaneUrl().isBlank()) {
            log.warn("yunxi.aistio.control-plane-url 未配置，跳过数据面注册（可改为在 aistio 控制台手动添加契约基址）");
            return;
        }
        this.instanceKey = UUID.randomUUID().toString();
        new Thread(this::registerWithRetry, "aistio-registrar").start();
    }

    private void registerWithRetry() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (tryRegister()) {
                return;
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("aistio 数据面注册在 {} 次重试后仍失败，控制面可能未启动；yunxi 将等待控制面后续拉取"
                + "（或在控制台手动添加契约基址兜底）", MAX_ATTEMPTS);
    }

    private boolean tryRegister() {
        try {
            DataPlaneInfo info = infoService.buildInfo();
            String routingKey = properties.getBaseUrl().replaceAll("/+$", "") + "/agentscope";

            AgentRegistrationRequest body = new AgentRegistrationRequest();
            body.setTenant(properties.getTenant());
            body.setNamespace(properties.getNamespace());
            body.setAgentKey(properties.getAgentKey());
            body.setDisplayName(properties.getAgentName());
            body.setInstanceKey(instanceKey);
            body.setFramework("yunxi-agent-platform");
            body.setFrameworkVersion(info.getVersion());
            body.setSdkVersion(info.getVersion());
            body.setCapabilities(info.getCapabilities());
            body.setLabels(defaultLabels(info));
            body.setRoutingKey(routingKey);
            body.setCapacity(0);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 新版 /api/v1/agent-registrations 不要求 X-Builder-Internal-Token（见类注释）

            String url = properties.getControlPlaneUrl().replaceAll("/+$", "") + "/api/v1/agent-registrations";
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                parseAndStoreCredential(response.getBody());
                log.info("已向 aistio 控制面注册数据面（agentKey={}, instanceKey={}）: {}",
                        properties.getAgentKey(), instanceKey, url);
                return true;
            }
            log.warn("aistio 注册返回非成功状态码: {}", response.getStatusCode());
        } catch (Exception e) {
            log.warn("aistio 数据面注册失败（控制面可能尚未就绪）: {}", e.getMessage());
        }
        return false;
    }

    private Map<String, String> defaultLabels(DataPlaneInfo info) {
        Map<String, String> labels = new HashMap<>(4);
        labels.put("platform", "yunxi-agent-platform");
        labels.put("runtime", "agentscope-java");
        labels.put("version", info.getVersion());
        return labels;
    }

    private void parseAndStoreCredential(String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        try {
            AgentRegistrationResponse resp = objectMapper.readValue(body, AgentRegistrationResponse.class);
            this.registrationCredential = resp.getRegistrationCredential();
            if (resp.getInstance() != null && resp.getInstance().getId() != null) {
                log.debug("aistio 注册实例 id={}", resp.getInstance().getId());
            }
        } catch (Exception e) {
            log.debug("解析 aistio 注册响应失败（不影响注册结果）: {}", e.getMessage());
        }
    }
}
