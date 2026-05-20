package io.yunxi.platform.business.nutrition.provider;

import io.yunxi.platform.business.nutrition.service.ProfessionExtractionService;
import io.yunxi.platform.spi.profile.UserProfileProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 营养领域用户画像提供者
 *
 * <p>
 * 实现框架层定义的 UserProfileProvider 接口，提供营养领域特定的用户画像数据。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class NutritionUserProfileProvider implements UserProfileProvider {

    /** 身份识别与画像演进服务 */
    @Autowired
    private ProfessionExtractionService professionExtractionService;

    /**
     * 获取指定用户的画像信息
     *
     * @param userId 用户ID
     * @return 用户画像数据
     */
    @Override
    public UserProfile getProfile(String userId) {
        return professionExtractionService.getProfile(userId);
    }
}
