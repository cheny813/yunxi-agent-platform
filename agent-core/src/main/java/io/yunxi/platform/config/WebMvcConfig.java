package io.yunxi.platform.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.yunxi.platform.security.auth.AuthInterceptor;

/**
 * Web MVC 统一配置
 *
 * <p>
 * 包含以下配置项：
 * <ul>
 * <li>认证拦截器配置</li>
 * <li>CORS 跨域配置</li>
 * <li>HTTP 消息转换器（UTF-8 编码）</li>
 * <li>视图控制器映射</li>
 * <li>静态资源配置</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 认证拦截器 */
    @Autowired
    private ObjectProvider<AuthInterceptor> authInterceptorProvider;

    /** 限流拦截器 */
    @Autowired
    private ObjectProvider<RateLimitInterceptor> rateLimitInterceptorProvider;

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
        if (authInterceptorProvider.getIfAvailable() != null) {
            registry.addInterceptor(authInterceptorProvider.getIfAvailable())
                    .addPathPatterns("/api/**")
                    .excludePathPatterns(
                            "/api/health",
                            "/api/actuator/**");
        }
        if (rateLimitInterceptorProvider.getIfAvailable() != null) {
            registry.addInterceptor(rateLimitInterceptorProvider.getIfAvailable())
                    .addPathPatterns("/api/**")
                    .excludePathPatterns(
                            "/api/health",
                            "/api/actuator/**");
        }
    }

    /**
     * 配置 CORS 跨域配置
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
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
