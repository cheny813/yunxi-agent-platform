package io.yunxi.platform.shared.codec;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 测试辅助：编解码器相关的构造助手。
 *
 * <p>本模块不启动 Spring 容器，故指标注册表以空提供者代替。</p>
 *
 * @author yunxi-agent-platform
 */
final class CodecTestSupport {

    private CodecTestSupport() {
    }

    /**
     * 返回一个不含任何指标的注册表提供者。
     *
     * @return 空提供者
     */
    static ObjectProvider<MeterRegistry> emptyRegistryProvider() {
        return new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);
    }

    /**
     * 构造一个不绑定指标注册表的编解码器实例。
     *
     * @return 编解码器
     */
    static RedisValueCodec newCodec() {
        return new RedisValueCodec(new CodecMetrics(emptyRegistryProvider()));
    }
}
