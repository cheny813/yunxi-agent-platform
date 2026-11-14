package io.yunxi.app.tenant;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.yunxi.app.config.TenantQuotaProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedisTenantStorageMeter} 行为验证：写入 INCR / 删除 DECR / 键正确；null 租户与 Redis 缺失静默跳过；
 * 超阈值发 WARN（不抛、不阻断）；fail-open。
 */
class RedisTenantStorageMeterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final TenantQuotaProperties props = new TenantQuotaProperties();
    private final ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);

    private RedisTenantStorageMeter meter() {
        when(redis.opsForValue()).thenReturn(ops);
        when(provider.getIfAvailable()).thenReturn(redis);
        return new RedisTenantStorageMeter(props, provider);
    }

    @Test
    void recordWrite_incrementsStorageKey() {
        when(ops.increment("yunxi:tenant-storage:t1")).thenReturn(3L);
        meter().recordWrite("t1");
        verify(ops).increment("yunxi:tenant-storage:t1");
    }

    @Test
    void recordDelete_decrementsStorageKey() {
        when(ops.decrement("yunxi:tenant-storage:t1")).thenReturn(1L);
        meter().recordDelete("t1");
        verify(ops).decrement("yunxi:tenant-storage:t1");
    }

    @Test
    void nullTenant_skipsRedis() {
        meter().recordWrite(null);
        verify(redis, never()).opsForValue();
    }

    @Test
    void missingRedis_skipsSilently() {
        when(provider.getIfAvailable()).thenReturn(null);
        RedisTenantStorageMeter m = new RedisTenantStorageMeter(props, provider);
        m.recordWrite("t1");
        m.recordDelete("t1");
        // 无异常、未触碰 redis
    }

    @Test
    void redisException_failOpenSkipsSilently() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("conn refused"));
        when(provider.getIfAvailable()).thenReturn(redis);
        RedisTenantStorageMeter m = new RedisTenantStorageMeter(props, provider);
        m.recordWrite("t1");
        m.recordDelete("t1");
        // 无异常（fail-open，异常在 adjust 内被吞）
    }

    @Test
    void overLimit_emitsWarnAndDoesNotThrow() {
        ListAppender<ILoggingEvent> appender = attachLogger();
        when(ops.increment("yunxi:tenant-storage:t1")).thenReturn(11L);
        props.setDefaultStorageBytesLimit(10L);
        meter().recordWrite("t1");
        verify(ops).increment("yunxi:tenant-storage:t1");
        boolean warned = appender.list.stream().anyMatch(e ->
                e.getLevel() == Level.WARN && e.getFormattedMessage().contains("已超软阈值"));
        assertThat(warned).isTrue();
        detachLogger(appender);
    }

    @Test
    void withinLimit_noWarn() {
        ListAppender<ILoggingEvent> appender = attachLogger();
        when(ops.increment("yunxi:tenant-storage:t1")).thenReturn(5L);
        props.setDefaultStorageBytesLimit(10L);
        meter().recordWrite("t1");
        boolean warned = appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN);
        assertThat(warned).isFalse();
        detachLogger(appender);
    }

    @Test
    void noLimitConfigured_noWarn() {
        ListAppender<ILoggingEvent> appender = attachLogger();
        when(ops.increment("yunxi:tenant-storage:t1")).thenReturn(999L);
        meter().recordWrite("t1");
        boolean warned = appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN);
        assertThat(warned).isFalse();
        detachLogger(appender);
    }

    private static ListAppender<ILoggingEvent> attachLogger() {
        Logger logger = (Logger) LoggerFactory.getLogger(RedisTenantStorageMeter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogger(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(RedisTenantStorageMeter.class);
        logger.detachAppender(appender);
        appender.stop();
    }
}
