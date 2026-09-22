package io.yunxi.platform.trace;

import org.springframework.beans.factory.support.StaticListableBeanFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.yunxi.platform.shared.codec.CodecMetrics;
import io.yunxi.platform.shared.codec.RedisValueCodec;

/**
 * 测试用的编解码器构造助手。
 *
 * <p>统一编解码器需要指标注册表，而单测不启动容器，故在此集中构造一个无指标注册表的实例。
 * 测试代码不应各自重复这段构造。</p>
 *
 * @author yunxi-agent-platform
 */
final class TestCodecs {

    private TestCodecs() {
    }

    /**
     * 构造一个不绑定指标注册表的编解码器实例。
     *
     * @return 编解码器
     */
    static RedisValueCodec newCodec() {
        return new RedisValueCodec(new CodecMetrics(
                new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class)));
    }
}
