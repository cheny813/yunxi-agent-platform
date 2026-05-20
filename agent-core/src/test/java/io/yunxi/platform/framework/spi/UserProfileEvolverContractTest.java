package io.yunxi.platform.framework.spi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;

/**
 * UserProfileEvolver SPI 契约测试
 */
public abstract class UserProfileEvolverContractTest extends AbstractSpiContractTest<UserProfileEvolver> {

    @Override
    protected Class<UserProfileEvolver> spiType() {
        return UserProfileEvolver.class;
    }

    @Test
    void evolve_nullProfile_shouldReturnNull() {
        List<Msg> messages = List.of(Msg.builder().textContent("test").build());
        UserProfile result = createInstance().evolve(null, messages);
        assertNull(result, "evolve(null, messages) 应返回 null");
    }

    @Test
    void evolve_emptyConversation_shouldReturnOriginalProfile() {
        UserProfile profile = new UserProfile("user1", List.of(),
                Map.of(), Map.of(), System.currentTimeMillis());

        UserProfile result = createInstance().evolve(profile, List.of());

        // 空对话应原样返回画像（或等价画像）
        assertNotNull(result, "evolve(profile, 空对话) 不应返回 null");
        assertEquals("user1", result.getUserId(), "userId 应保持不变");
    }

    @Test
    void consolidate_nullProfile_shouldReturnNull() {
        UserProfile result = createInstance().consolidate(null);
        assertNull(result, "consolidate(null) 应返回 null");
    }

    @Test
    void consolidate_shouldNotThrowWithEmptyProfile() {
        UserProfile profile = new UserProfile("user1", List.of(),
                Map.of(), Map.of(), System.currentTimeMillis());
        assertDoesNotThrow(() -> createInstance().consolidate(profile),
                "consolidate() 不应抛异常");
    }
}
