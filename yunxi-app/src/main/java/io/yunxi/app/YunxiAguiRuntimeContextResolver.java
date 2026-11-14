package io.yunxi.app;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.yunxi.runtime.ProtocolUserIdResolver;
import io.yunxi.runtime.tenant.TenantConfig;
import io.yunxi.runtime.tenant.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 入口平面。AG-UI 协议的身份解析，是四协议统一身份的最后一环。
 *
 * <p>实现 AgentScope-Java 的 {@code AguiRuntimeContextResolver} 扩展点：框架 {@code AguiMvcController}
 * 经 {@code ObjectProvider.getIfAvailable()} 拾取本 Bean，把解析出的 {@link RuntimeContext}（含 userId）
 * 合并进 AG-UI 运行（框架以本上下文为基，再覆盖 sessionId = threadId，userId 被保留）。</p>
 *
 * <p>身份归一逻辑复用 {@link ProtocolUserIdResolver}（认证主体名 -> 请求头 {@code X-Yunxi-User}），
 * 与 web / Chat Completions 共用同一份实现，不另立第二份身份解析。</p>
 *
 * <p>关键线程模型：AG-UI 控制器把请求处理提交到独立线程池（非 HTTP 请求线程），
 * Spring Security 的 SecurityContext ThreadLocal 在异步线程为 null；故优先从请求属性读取由
 * {@link UserIdCaptureFilter} 在请求线程上解析并暂存的 userId（同一请求对象，无竞态），
 * 仅在取不到时回退到 SecurityContext / 请求头。userId 为 null 时返回 null，由框架回退到无用户的上下文。</p>
 *
 * <p>租户解析：复用 {@link TenantResolver}（HTTP 头，与 web / Chat Completions 同源），经
 * {@link TenantConfig#defaultTenant()} 回落后写入 RuntimeContext 的 {@code tenant} 槽，
 * 供 {@link YunxiGuardedAguiAgentAdapter} 在 run 入口取出做越权拦截；AG-UI 异步线程下租户随
 * RuntimeContext 数据传递（线程安全），不依赖 ThreadLocal 解析。</p>
 */
@Component
public class YunxiAguiRuntimeContextResolver implements AguiRuntimeContextResolver {

    private static final String USER_HEADER = "X-Yunxi-User";

    private final ProtocolUserIdResolver userIdResolver;
    private final TenantResolver tenantResolver;
    private final TenantConfig tenantConfig;

    /** 注入身份归一解析器（与 web / Chat Completions 共用）与租户解析/配置。 */
    public YunxiAguiRuntimeContextResolver(ProtocolUserIdResolver userIdResolver,
            TenantResolver tenantResolver, TenantConfig tenantConfig) {
        this.userIdResolver = userIdResolver;
        this.tenantResolver = tenantResolver;
        this.tenantConfig = tenantConfig;
    }

    /** 解析 AG-UI 请求的 userId 与 tenant：优先取请求线程暂存的属性，回退到安全主体 / 请求头；无 userId 则返 null。 */
    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest<?> request) {
        String userId = userIdFromRequestAttribute(request);
        if (userId == null) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String headerUser = request != null ? request.firstHeader(USER_HEADER) : null;
            userId = userIdResolver.resolve(authentication, headerUser);
        }
        if (userId == null) {
            return null;
        }
        String tenant = null;
        if (request != null) {
            tenant = tenantResolver.resolve(singleValueHeaders(request.getHeaders()));
        }
        if (tenant == null) {
            tenant = tenantConfig.defaultTenant();
        }
        RuntimeContext.Builder builder = RuntimeContext.builder().userId(userId);
        if (tenant != null) {
            builder.put("tenant", tenant);
        }
        return builder.build();
    }

    /** 从请求属性读取由 {@link UserIdCaptureFilter} 在请求线程暂存的 userId。 */
    private String userIdFromRequestAttribute(AguiRuntimeContextRequest<?> request) {
        Object nativeRequest = request != null ? request.getNativeRequest() : null;
        if (nativeRequest instanceof HttpServletRequest servletRequest) {
            Object value = servletRequest.getAttribute(UserIdCaptureFilter.USER_ID_ATTR);
            if (value instanceof String userId && !userId.isBlank()) {
                return userId;
            }
        }
        return null;
    }

    /** 将 AG-UI 多值头 Map 降级为单值 Map，供 TenantResolver（接收 Map<String,String>）解析租户。 */
    private Map<String, String> singleValueHeaders(Map<String, List<String>> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                out.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return out;
    }
}
