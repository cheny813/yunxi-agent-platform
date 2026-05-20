package io.yunxi.platform.framework.spi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.yunxi.platform.spi.profile.UserProfileProvider;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;

/**
 * UserProfileProvider SPI 契约测试
 */
public abstract class UserProfileProviderContractTest extends AbstractSpiContractTest<UserProfileProvider> {

    @Override
    protected Class<UserProfileProvider> spiType() {
        return UserProfileProvider.class;
    }

    @Test
    void getProfile_nullUserId_shouldReturnNull() {
        UserProfile result = createInstance().getProfile(null);
        assertNull(result, "getProfile(null) 应返回 null");
    }

    @Test
    void getProfile_unknownUser_shouldReturnNullOrEmptyProfile() {
        UserProfile result = createInstance().getProfile("nonexistent_user_12345");
        // 未知用户可以返回 null 或空画像，都是合理的
        // 只要不抛异常即可
    }

    @Test
    void getProfile_shouldNotThrow() {
        assertDoesNotThrow(() -> createInstance().getProfile("anyUser"),
                "getProfile() 不应抛异常");
    }
}
