package io.yunxi.ingress;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * 分片转发 SPI：由运行时（yunxi-app）的 {@code AgentForwardingRouter} 实现并注册为 bean。
 *
 * <p>入口织面（yunxi-ingress）仅依赖本接口，避免反向依赖 yunxi-app 形成 Maven 模块环；
 * 本实例身份、哈希环、Nacos 发现等全部封装在实现侧。</p>
 *
 * <p>fail-open 契约：分片关闭时（无实现 bean）入口经 {@code ObjectProvider} 拿到空 → 本地兜底；
 * 即使分片开启，{@link #forwardIfNeeded} 在 owned / 无 owner 信息 / owner 不可达 / 转发异常时也返回
 * {@code null}，由调用方本地处理，不静默丢失请求、不 5xx。</p>
 */
public interface ShardForwarder {

    /**
     * 若 {@code agentId} 不属于本实例分片，将其转发至 owner 实例的 {@code baseUrl} 并透传原始请求；
     * 本实例拥有或无 owner 信息时返回 {@code null}（调用方本地处理 / fail-open）。
     *
     * @param agentId 目标 agent 稳定标识（与一致性哈希环入参一致）
     * @param path    相对路径（如 {@code /api/conversations}），拼接 owner {@code baseUrl} 转发
     * @param method  HTTP 方法
     * @param body    请求体原文（非空时以此原文转发）
     * @param headers 透传请求头（敏感头可在实现侧裁剪）
     * @return owner 的原始 HTTP 响应；本地处理时返回 {@code null}
     */
    ResponseEntity<String> forwardIfNeeded(String agentId, String path, HttpMethod method,
            String body, HttpHeaders headers);
}
