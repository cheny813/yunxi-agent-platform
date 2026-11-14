package io.yunxi.app;

import io.agentscope.spring.boot.agui.common.AguiProperties;
import io.yunxi.app.shard.AgentForwardingRouter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.GenericFilterBean;

/**
 * AG-UI 跨实例流式转发过滤器（框架 AG-UI 入口仍由框架 {@code AguiRestController} 驱动，本过滤器在 HTTP 边界拦截，
 * 零改框架 bean、零子类化——因框架 {@code AguiMvcController} / {@code AguiRestController} 构造分别为 private / package-private，
 * 不可子类化委托，且框架 {@code @ConditionalOnMissingBean} 按类型匹配，无法由 yunxi 取代，故不能套用 ChatCompletions 的
 * 「子类覆盖」范式，故改用过滤器在 HTTP 边界拦截实现跨实例转发）。
 *
 * <p>行为：① 仅对 AG-UI run 端点（{@link AguiProperties#getPathPrefix()} + {@code /run} 与 {@code /run/{agentId}}）生效；
 * ② 复用 {@link AgentForwardingRouter} 的分片真相与 owner 寻址；③ non-owned → 经 {@code forwardStreamTo} 把 owner 的 SSE 以
 * {@link MediaType#TEXT_EVENT_STREAM} raw 透传写入本响应（不调用 {@code chain}，避免框架本地 404）；④ owned / 分片关闭 /
 * 无 owner 信息 → {@code chain} 交框架本地处理（fail-open）。</p>
 *
 * <p>agentId 解析与框架 {@code AguiRestController} 一致：{@code enablePathRouting} 时优先 path，否则 header，再 default。
 * body 经 {@link java.util.function.Supplier} 惰性读取，仅在实际转发时消费，owned 路径不提前读取请求体（框架可正常读取）。</p>
 */
public class AguiForwardingFilter extends GenericFilterBean {

    private static final Logger log = LoggerFactory.getLogger(AguiForwardingFilter.class);

    private final ObjectProvider<AgentForwardingRouter> forwardingRouterProvider;
    private final AguiProperties props;

    public AguiForwardingFilter(ObjectProvider<AgentForwardingRouter> forwardingRouterProvider, AguiProperties props) {
        this.forwardingRouterProvider = forwardingRouterProvider;
        this.props = props;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = relativePath(request);
        if (!isAguiRunPath(path)) {
            chain.doFilter(req, res);
            return;
        }
        AgentForwardingRouter router = forwardingRouterProvider.getIfAvailable();
        if (router == null) {
            chain.doFilter(req, res); // 分片关闭，fail-open 本地
            return;
        }
        String agentId = resolveAgentId(request, path);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            boolean forwarded = router.forwardStreamTo(
                    response.getOutputStream(), agentId, path, HttpMethod.POST,
                    () -> {
                        try {
                            return readBody(request.getInputStream());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, toHttpHeaders(request));
            if (!forwarded) {
                // 转发被拒（owned / 无 owner 信息 / owner 不可达）：本地兜底，交框架处理（out 未写入，安全）
                chain.doFilter(req, res);
            }
            // forwarded=true：响应已由 router 泵送写入，不调用 chain
        } catch (RuntimeException e) {
            // 转发已尝试但 I/O / 传输失败（out 可能已部分写入）：不调用 chain，交由容器关闭连接
            log.warn("[AG-UI-Filter] forward failed for agentId={}: {}", agentId, e.getMessage());
        }
    }

    private boolean isAguiRunPath(String path) {
        String prefix = props.getPathPrefix();
        return path.equals(prefix + "/run") || path.startsWith(prefix + "/run/");
    }

    private String resolveAgentId(HttpServletRequest request, String path) {
        String headerAgentId = request.getHeader(props.getAgentIdHeader());
        String pathAgentId = extractPathAgentId(path);
        if (props.isEnablePathRouting() && pathAgentId != null) {
            return pathAgentId;
        }
        if (headerAgentId != null && !headerAgentId.isBlank()) {
            return headerAgentId;
        }
        return props.getDefaultAgentId();
    }

    private String extractPathAgentId(String path) {
        String prefix = props.getPathPrefix();
        String marker = prefix + "/run/";
        if (path.startsWith(marker) && path.length() > marker.length()) {
            return path.substring(marker.length());
        }
        return null;
    }

    private String relativePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toString(StandardCharsets.UTF_8);
    }

    private HttpHeaders toHttpHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.add(name, request.getHeader(name));
        }
        return headers;
    }
}
