package io.yunxi.platform.framework.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * SPI 契约测试基类
 * <p>
 * 所有 SPI 实现者应继承此类并实现 {@link #createInstance()}，以确保满足基本契约。
 * 遵循"架构师-程序员"分离原则：契约由架构师定义，实现者只需证明满足契约。
 * </p>
 *
 * @param <T> SPI 接口类型
 */
public abstract class AbstractSpiContractTest<T> {

    /**
     * 创建被测实例
     */
    protected abstract T createInstance();

    /**
     * 获取 SPI 类型
     */
    protected abstract Class<T> spiType();

    @Test
    void instanceNotNull() {
        assertNotNull(createInstance(), "SPI 实例不能为 null");
    }

    @Test
    void spiTypeDefined() {
        assertNotNull(spiType(), "SPI 类型不能为 null");
    }
}
