package io.yunxi.platform.infra.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.yunxi.platform.shared.security.AuthInterceptor;

/**
 * Web MVC 统一配置
 * 
 * <p>
 * 整合以下功能：
 * <ul>
 * <li>认证拦截器配置</li>
 * <li>CORS 跨域配置</li>
 * <li>HTTP 消息转换器（UTF-8 编码）</li>
 * <li>视图控制器映射</li>
 * <li>静态资源处理</li>
 * </ul>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
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
     * 拦截所有 API 请求进行身份验证，排除健康检查和监控端点
     * 
     * @param registry 拦截器注册表
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
     * 配置CORS跨域请求
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
     * 配置HTTP消息转换器，统一使用UTF-8编码
     * <p>
     * 确保所有HTTP响应（JSON、字符串等）都使用统一的UTF-8编码
     * 避免中文乱码问题，符合现代Web应用的国际标准
     * </p>
     *
     * @param converters 现有的消息转换器列表
     */
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 遍历所有消息转换器，设置UTF-8编码
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter) {
                // JSON转换器：设置默认字符集为UTF-8
                ((MappingJackson2HttpMessageConverter) converter).setDefaultCharset(StandardCharsets.UTF_8);
            }
            if (converter instanceof StringHttpMessageConverter) {
                // 字符串转换器：设置默认字符集为UTF-8
                ((StringHttpMessageConverter) converter).setDefaultCharset(StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * 配置视图控制器，映射静态页面路径
     * <p>
     * 将不带扩展名的路径映射到对应的静态HTML文件
     * </p>
     *
     * @param registry 视图控制器注册表
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 映射静态页面路径到对应的HTML文件
        registry.addViewController("/chat").setViewName("forward:/chat.html");
        registry.addViewController("/chat-demo").setViewName("forward:/chat-demo.html");
        registry.addViewController("/recipe-automation-new").setViewName("forward:/recipe-automation-new.html");
    }

    /**
     * 配置静态资源处理器
     * <p>
     * 处理静态资源请求（如HTML、CSS、JS、图片等），
     * 将所有静态资源映射到classpath:/static/目录下
     * </p>
     * <p>
     * 注意：API路径（如/agents, /conversations）由对应的Controller处理
     * 不需要特殊排除配置，因为AgentController支持两种路径格式
     * </p>
     *
     * @param registry 资源处理器注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 配置静态资源处理：所有请求都映射到static目录
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(0); // 禁用缓存，便于开发调试
    }
}
