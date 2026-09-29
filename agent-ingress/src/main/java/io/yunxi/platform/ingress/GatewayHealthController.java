package io.yunxi.platform.ingress;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.beans.factory.annotation.Value;

/**
 * 统一健康聚合端点（{@code /api/health}）。
 *
 * <p>此前平台无聚合健康端点，5 处 {@code /health} 分散在各自 Controller。本端点将关键依赖信号
 * 收敛为一处：Redis / MySQL 可达性走底层连接直接探测（不依赖 HTTP 自调用），a2a / aistio 开关
 * 读取运行期配置。任一核心依赖（Redis / MySQL）不可达即整体 {@code DOWN}。</p>
 */
@RestController
@RequestMapping("/api/health")
public class GatewayHealthController {

    private final ObjectProvider<RedisConnectionFactory> redisProvider;
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final boolean a2aEnabled;
    private final boolean aistioEnabled;

    public GatewayHealthController(ObjectProvider<RedisConnectionFactory> redisProvider,
                                   ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                                   @Value("${yunxi.a2a.enabled:true}") boolean a2aEnabled,
                                   @Value("${yunxi.aistio.enabled:false}") boolean aistioEnabled) {
        this.redisProvider = redisProvider;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.a2aEnabled = a2aEnabled;
        this.aistioEnabled = aistioEnabled;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("redis", checkRedis());
        signals.put("mysql", checkMysql());
        signals.put("a2a", a2aEnabled);
        signals.put("aistio", aistioEnabled);

        boolean up = Boolean.TRUE.equals(signals.get("redis"))
                && Boolean.TRUE.equals(signals.get("mysql"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", up ? "UP" : "DOWN");
        result.put("signals", signals);
        return result;
    }

    private boolean checkRedis() {
        try {
            RedisConnectionFactory factory = redisProvider.getIfAvailable();
            if (factory == null) {
                return false;
            }
            factory.getConnection().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkMysql() {
        try {
            JdbcTemplate template = jdbcTemplateProvider.getIfAvailable();
            if (template == null) {
                return false;
            }
            template.execute("SELECT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
