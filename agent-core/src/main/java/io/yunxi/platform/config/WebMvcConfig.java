package io.yunxi.platform.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.yunxi.platform.security.auth.EdgeAuthenticationFilter;
import lombok.extern.slf4j.Slf4j;

/**
 * Web MVC 统一配置
 *
 * <p>
 * 包含以下配置项：
 * <ul>
 * <li>边缘鉴权（{@link EdgeAuthenticationFilter}，servlet 过滤器，在拦截器之前执行）</li>
 * <li>CORS 跨域配置（默认拒绝，须显式配置来源）</li>
 * <li>HTTP 消息转换器（UTF-8 编码）</li>
 * <li>视图控制器映射</li>
 * <li>静态资源配置</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 限流拦截器 */
    @Autowired
    private ObjectProvider<RateLimitInterceptor> rateLimitInterceptorProvider;

    /**
     * CORS 允许的前端源（逗号分隔）。为空时回退为「拒绝任何源」（不开放跨域）并告警，
     * 生产/联调环境须显式配置为已知前端源（如 http://localhost:5173、各部署域名），
     * 避免任意源携带凭据跨域访问。
     */
    @Value("${yunxi.web.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    /**
     * 配置认证拦截器
     *
     * <p>
     * 拦截器负责 API 请求的认证检查，
     * 排除健康检查和监控端点
     *
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (rateLimitInterceptorProvider.getIfAvailable() != null) {
            registry.addInterceptor(rateLimitInterceptorProvider.getIfAvailable())
                    .addPathPatterns("/api/**")
                    .excludePathPatterns(
                            "/api/health",
                            "/api/channels",
                            "/api/channels/**",
                            "/api/actuator/**",
                            "/api/traces/diagnostics");
        }
    }

    /**
     * 配置 CORS 跨域配置
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = resolveCorsOrigins();
        boolean hasOrigins = origins.length > 0;
        boolean wildcardOnly = hasOrigins
                && Arrays.stream(origins).allMatch(o -> "*".equals(o.trim()));
        CorsRegistration registration = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "HEAD")
                .allowedHeaders("*")
                .exposedHeaders("X-Total-Count", "X-Request-Id", "Content-Disposition")
                .maxAge(3600);
        if (hasOrigins && !wildcardOnly) {
            // 仅当来源为显式（非通配符）列表时才允许携带凭据。
            // allowedOrigins 必须显式列出、不可为 null/空，否则 Spring 在 allowCredentials=true 时
            // 因 allowedOrigins 为空而抛出 IllegalArgumentException
            // （"When allowCredentials is true, allowedOrigins cannot contain the special value *"）。
            registration.allowedOrigins(origins).allowedOriginPatterns(origins).allowCredentials(true);
        } else if (wildcardOnly) {
            // 通配符来源仅允许无凭据跨域，规避「allowCredentials=true 时 allowedOrigins 不能含 *」的限制。
            registration.allowedOrigins("*").allowCredentials(false);
        } else {
            // 未配置来源：默认拒绝任何源（不开放跨域），且不携带凭据，避免空 allowedOrigins 触发校验异常。
            registration.allowedOrigins(new String[0]).allowCredentials(false);
        }
    }

    /**
     * 解析 CORS 允许源。配置了 {@code yunxi.web.cors.allowed-origins} 时使用配置值；
     * 未配置时回退为「拒绝任何源」（空数组，不开放跨域）并打印告警，提示运维显式列出来源。
     *
     * @return 允许源模式数组
     */
    private String[] resolveCorsOrigins() {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
            log.warn("CORS 未显式配置 yunxi.web.cors.allowed-origins，默认拒绝任何源（不开放跨域）。"
                    + "生产/联调环境应通过配置显式列出已知前端源（如 http://localhost:5173），避免任意源携带凭据访问。");
            return new String[0];
        }
        return Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    /**
     * 配置 HTTP 消息转换器，统一使用 UTF-8 编码
     * <p>
     * 确保所有 HTTP 响应（JSON、文字等）都使用统一的 UTF-8 编码，
     * 避免中文乱码问题，同时解决部分 Web 服务的中文乱码问题
     * </p>
     *
     * @param converters 现有的 HTTP 消息转换器列表
     */
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 遍历所有 HTTP 消息转换器，设置 UTF-8 编码
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter) {
                // JSON 转换器：设置默认字符集为 UTF-8
                ((MappingJackson2HttpMessageConverter) converter).setDefaultCharset(StandardCharsets.UTF_8);
            }
            if (converter instanceof StringHttpMessageConverter) {
                // 文字转换器：设置默认字符集为 UTF-8
                ((StringHttpMessageConverter) converter).setDefaultCharset(StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * 配置视图控制器，设置静态页面的路由
     * <p>
     * 将不需要逻辑处理的页面路由指向对应的静态 HTML 文件
     * </p>
     *
     * @param registry 视图控制器注册器
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 设置静态页面的路由
        registry.addViewController("/chat").setViewName("forward:/chat.html");
        registry.addViewController("/chat-demo").setViewName("forward:/chat-demo.html");
    }

    /**
     * 配置静态资源处理器
     * <p>
     * 处理静态资源请求（如 HTML、CSS、JS、图片等），
     * 将所有静态资源映射到 classpath:/static/ 目录
     * </p>
     * <p>
     * 注意：API 路由（如 /agents, /conversations）会由对应的 Controller 处理，
     * 不需要在此额外配置，因为我们 AgentController 处理两种类型路由
     * </p>
     *
     * @param registry 静态资源处理器注册器
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 配置静态资源映射：所有请求映射到 static 目录
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(0); // 禁用缓存，立即更新
    }

    /**
     * 配置 MVC 异步请求执行器
     * <p>
     * 替换默认的 {@code SimpleAsyncTaskExecutor}，因为默认的线程池不适合高并发场景，
     * 使用线程池策略处理异步请求（如 SSE 推送响应），避免进程阻塞导致超时
     * </p>
     *
     * @param configurer 异步配置器
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncExecutor());
        // MVC 异步请求默认使用来自 ChatAppService 的 chat-timeout-seconds 配置（默认 480 秒）
        // 此处不设置具体数值以避免提前终止 SSE 连接
        configurer.setDefaultTimeout(0);
    }

    /**
     * MVC 异步配置专用线程池
     * <p>
     * 专用池：Spring MVC 异步处理（如 SSE、DeferredResult 等），
     * 不 {@code @Async} 类型线程池共享，避免相互干扰
     * </p>
     *
     * @return AsyncTaskExecutor 异步执行器
     */
    @Bean
    public AsyncTaskExecutor mvcAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("MvcAsync-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
