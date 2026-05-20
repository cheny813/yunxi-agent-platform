package io.yunxi.platform.agent.ruleengine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 测试基类，提供通用的测试配置和工具方法
 */
@ExtendWith({SpringExtension.class, MockitoExtension.class})
public abstract class BaseTest {

    @BeforeEach
    void setup() {
        // 通用的测试初始化逻辑
    }

    /**
     * 断言两个对象的内容相等（深层比较）
     */
    protected <T> void assertDeepEquals(T expected, T actual) {
        org.assertj.core.api.Assertions.assertThat(actual)
                .usingRecursiveComparison()
                .isEqualTo(expected);
    }

    /**
     * 断言异常被正确抛出
     */
    protected void assertThrowsException(Class<? extends Throwable> expectedException,
                                       Runnable operation, 
                                       String expectedMessage) {
        org.assertj.core.api.Assertions.assertThatThrownBy(operation::run)
                .isInstanceOf(expectedException)
                .hasMessageContaining(expectedMessage);
    }

    /**
     * 创建测试数据构建器
     */
    protected <T> T createTestData(Class<T> clazz) {
        // 可以使用反射或其他工具创建测试数据
        // 这里可以整合Builder模式或Factory模式
        return null;
    }
}