package io.yunxi.platform.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import io.yunxi.platform.shared.codec.RedisTemplates;
import io.yunxi.platform.shared.codec.RedisValueCodec;

/**
 * {@link TraceStore} 的统一自动装配。
 *
 * <p>把 Redis 实现与内存实现放在同一个配置类里，确保两者的装配顺序确定：
 * 先尝试注册 Redis 实现（跨实例可见），仅当 Redis 实现不存在时才回退到内存实现。
 * 跨类拆分两个 {@code @Configuration} 会让 {@code @ConditionalOnMissingBean} 的评估时机
 * 依赖配置类的处理顺序，而组件扫描对嵌套配置类的发现顺序并不保证，导致 Redis 实现
 * 在某些部署下永不装配。</p>
 *
 * <p><b>Redis 可用性判定时机</b>：本类是普通 {@code @Configuration}（经组件扫描加载），
 * 其处理早于 Spring Boot 的自动配置阶段，因此 {@code RedisConnectionFactory}（由
 * {@code RedisAutoConfiguration} 创建）在本类被解析时尚不存在。若用
 * {@code @ConditionalOnBean(RedisConnectionFactory.class)} 在注册期判定，会恒为 false，
 * 误回退到内存实现。故改在 <b>bean 创建期</b>通过 {@link ObjectProvider#getIfAvailable()}
 * 取 {@code RedisConnectionFactory}：此时所有 bean 定义（含自动配置）已注册，能正确取到；
 * 取不到（未配 Redis）则方法返回 {@code null}，Spring 不注册该 bean，由内存实现兜底。</p>
 *
 * <p><b>连接来源</b>：复用全局自动装配的 {@link RedisConnectionFactory}
 * （连接参数由 {@code config/redis.yml} 的 {@code spring.data.redis.*} 统一提供），
 * 本类不自建连接工厂 —— 连接参数只允许有一个来源。</p>
 *
 * <p><b>编解码</b>：键用明文 {@link StringRedisSerializer}，以便与外部工具注入的合成轨迹
 * 格式一致；值统一走 {@link RedisValueCodec}，不附加类型标识。</p>
 */
@Configuration(proxyBeanMethods = false)
public class TraceStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TraceStoreAutoConfiguration.class);

    /**
     * 单一工厂方法：任何配置下都恰好产出一个 TraceStore 实例。
     *
     * <p>不用两个竞争的 @Bean 加 @ConditionalOnMissingBean 判定：当某个 bean 定义被注册
     * 但工厂方法返回 null 时，条件判定会误判、兜底失效，容器里最终可能没有可用的 TraceStore。
     * 单一方法内部选择实现可避免这类装配竞态。</p>
     *
     * <p>选择逻辑：显式检测到 RedisConnectionFactory 且未指定 memory 时优先用 RedisTraceStore
     * （跨实例共享）；其余情况（无 Redis 或显式 memory）回退 InMemoryTraceStore，
     * 显式要求 redis 却无 Redis 时仅告警不失败。</p>
     */
    @Bean
    @ConditionalOnMissingBean(TraceStore.class)
    public TraceStore traceStore(ObjectProvider<RedisConnectionFactory> redisConnectionFactory,
                                 Environment environment,
                                 RedisValueCodec valueCodec) {
        RedisConnectionFactory connectionFactory = redisConnectionFactory.getIfAvailable();
        String type = environment.getProperty("conversation.trace-storage-type", "redis");
        if (connectionFactory != null && !"memory".equalsIgnoreCase(type)) {
            RedisTemplate<String, Object> template = RedisTemplates.objectTemplate(connectionFactory, valueCodec);
            log.warn("TraceStore 装配: RedisTraceStore (复用全局 RedisConnectionFactory) 已创建，目标 Redis 实例={}，轨迹键前缀={}",
                    RedisTemplates.endpointOf(connectionFactory), RedisTraceStore.KEY_PREFIX);
            return new RedisTraceStore(template, valueCodec);
        }
        if (connectionFactory == null && "redis".equalsIgnoreCase(type)) {
            log.warn("TraceStore 装配: 显式要求 redis 但未检测到 RedisConnectionFactory，回退 InMemoryTraceStore（内存降级：轨迹仅存本进程、重启即失，查不到任何键属正常）");
        } else {
            log.warn("TraceStore 装配: InMemoryTraceStore (内存) 已创建（未配 Redis 的降级模式，轨迹仅存本进程、重启即失，查不到任何键属正常）");
        }
        return new InMemoryTraceStore();
    }

    /**
     * 轨迹归集器：全平台唯一的归集入口，供各执行通道共用。
     *
     * <p>刻意声明为 Bean 而非让各通道自行构造：归集是一处横切关切，若每个通道各自 new 一个，
     * 就会出现通道间的行为差异（走某接口有轨迹、走另一接口没有），而这类差异排查成本极高。</p>
     *
     * @param composer   归集器（事件流 → 语义快照）
     * @param traceStore 轨迹存储
     * @return 归集器
     */
    @Bean
    @ConditionalOnMissingBean(TraceCollector.class)
    public TraceCollector traceCollector(TraceComposer composer, TraceStore traceStore) {
        return new TraceCollector(composer, traceStore);
    }
}
