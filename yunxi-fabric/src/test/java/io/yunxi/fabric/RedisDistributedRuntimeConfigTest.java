package io.yunxi.fabric;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// Wiring must use the global Lettuce factory, never a self-managed Jedis client.
// Framework LettuceClientAdapter.of(RedisClient) eagerly calls redisClient.connect().sync() at build time,
// so we stub that connection chain (no real Redis) and only assert the type/source of the assembly.
class RedisDistributedRuntimeConfigTest {

    @Test
    void wiringUsesGlobalLettuceFactoryNotSelfManagedJedis() {
        LettuceConnectionFactory lcf = mock(LettuceConnectionFactory.class);
        RedisClient redisClient = mock(RedisClient.class);
        StatefulRedisConnection<String, String> conn = mock(StatefulRedisConnection.class);
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(redisClient.connect()).thenReturn(conn);
        when(conn.sync()).thenReturn(commands);
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.getConnectionFactory()).thenReturn(lcf);
        when(lcf.getNativeClient()).thenReturn(redisClient);

        RedisDistributedRuntimeConfig config = new RedisDistributedRuntimeConfig(template, "agentscope:session:");

        assertNotNull(config.distributedStore(), "DistributedStore must be assembled");
        assertInstanceOf(
                RedisAgentStateStore.class,
                config.distributedStore().agentStateStore(),
                "agentStateStore must be framework-native RedisAgentStateStore (global Lettuce)");
        assertInstanceOf(
                YunxiRedisBaseStore.class,
                config.distributedStore().baseStore(),
                "baseStore must be yunxi self-impl over global StringRedisTemplate, not framework Jedis RedisStore");
        assertInstanceOf(YunxiRedisBaseStore.class, config.sharedChunkStore());
    }

    @Test
    void throwsWhenGlobalFactoryIsNotLettuce() {
        RedisConnectionFactory cf = mock(RedisConnectionFactory.class);
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.getConnectionFactory()).thenReturn(cf);

        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> new RedisDistributedRuntimeConfig(template, "agentscope:session:"));
        assertTrue(
                ex.getMessage().contains("LettuceConnectionFactory"),
                "non-Lettuce factory must throw a clear IllegalStateException naming LettuceConnectionFactory");
    }
}
