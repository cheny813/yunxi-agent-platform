package io.yunxi.app;

import io.yunxi.runtime.ProtocolUserIdResolver;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * 应用平面。平台安全配置。默认策略：
 * <ul>
 *     <li>除 {@code /actuator/health} 外，所有 HTTP 端点必须认证；</li>
 *     <li>使用 HTTP Basic 认证（无状态，不创建会话）；</li>
 *     <li>关闭 CSRF（面向无浏览器的 API 客户端）；</li>
 *     <li>凭据由 {@link SecurityProperties} 提供，口令缺省时拒绝启动。</li>
 * </ul>
 *
 * <p>企业部署可通过覆盖 {@link UserDetailsService} / {@link SecurityFilterChain} Bean 接入 LDAP、OAuth2、API 网关等。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private final SecurityProperties properties;
    private final ProtocolUserIdResolver userIdResolver;

    /** 注入安全属性与身份归一解析器。 */
    public SecurityConfig(SecurityProperties properties, ProtocolUserIdResolver userIdResolver) {
        this.properties = properties;
        this.userIdResolver = userIdResolver;
    }

    /**
     * 主安全过滤链：除 {@code /actuator/health} 外全部端点需认证，HTTP Basic 无状态会话，
     * 关闭 CSRF，并在鉴权后挂 {@link UserIdCaptureFilter} 解析并暂存 userId。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        requireConfiguredPassword();
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // IM 渠道回调端点：真实性由框架 DingTalkCallbackCrypto 验签保证（非法签名控制器返回 401），
                        // 不挂 HTTP Basic，避免机器人无法携带凭证；其余端点仍需认证。
                        .requestMatchers("/api/channels/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .addFilterAfter(new UserIdCaptureFilter(userIdResolver), AuthorizationFilter.class);
        return http.build();
    }

    /** 内存用户明细服务：用配置中的用户名 / BCrypt 编码口令构建单用户（角色 PLATFORM）。 */
    @Bean
    public UserDetailsService userDetailsService() {
        requireConfiguredPassword();
        UserDetails user = User.withUsername(properties.getUserName())
                .password(passwordEncoder().encode(properties.getUserPassword()))
                .roles("PLATFORM")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        // 显式装配：上下文中若存在其它 PasswordEncoder Bean（来自 Spring AI / 框架依赖），
        // DaoAuthenticationProvider 默认会选择 DelegatingPasswordEncoder 而非本平台的 BCrypt，导致口令永远不匹配。
        // 此处把编码与校验锁定到同一 BCrypt 实例，避免该歧义。
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /** BCrypt 口令编码器（与 {@link #authenticationProvider()} 锁定同一实例）。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 启动期守卫：口令未配置时立即拒绝启动，避免以空口令暴露端点。 */
    private void requireConfiguredPassword() {
        if (properties.getUserPassword() == null || properties.getUserPassword().isBlank()) {
            throw new IllegalStateException(
                    "yunxi.security.user-password 未配置：平台拒绝以空口令启动。"
                            + "请通过 yunxi.security.user-password（或环境变量 YUNXI_SECURITY_USER_PASSWORD）设置访问口令。");
        }
    }
}
