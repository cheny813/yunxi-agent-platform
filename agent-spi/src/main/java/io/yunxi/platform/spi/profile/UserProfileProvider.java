package io.yunxi.platform.spi.profile;

import java.util.List;
import java.util.Map;

/**
 * 用户画像提供者接口（SPI）
 *
 * <p>框架层定义的抽象接口，由业务层实现。遵循依赖倒置原则。</p>
 *
 * <p>用户画像包含多维身份、个人上下文和社会关系标签。
 * 话题由问题内容决定，不由身份决定。</p>
 *
 * @author yunxi-agent-platform
 * @version 3.0.0
 */
public interface UserProfileProvider {

    /**
     * 获取用户画像
     *
     * @param userId 用户ID
     * @return 用户画像，如果用户不存在返回 null
     */
    UserProfile getProfile(String userId);

    /**
     * 用户画像数据
     */
    class UserProfile {
        private String userId;
        private List<Identity> identities;
        private Map<String, String> personalContext;
        private Map<String, String> socialRelations;
        private long updatedAt;

        public UserProfile(String userId, List<Identity> identities, Map<String, String> personalContext,
                          Map<String, String> socialRelations, long updatedAt) {
            this.userId = userId;
            this.identities = identities;
            this.personalContext = personalContext;
            this.socialRelations = socialRelations;
            this.updatedAt = updatedAt;
        }

        public String getUserId() {
            return userId;
        }

        public List<Identity> getIdentities() {
            return identities;
        }

        public Map<String, String> getPersonalContext() {
            return personalContext;
        }

        public Map<String, String> getSocialRelations() {
            return socialRelations;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        /**
         * 获取指定类别的身份列表
         */
        public List<Identity> getIdentitiesByCategory(String category) {
            return identities == null ? List.of()
                    : identities.stream()
                            .filter(id -> category.equals(id.getCategory()))
                            .toList();
        }

        /**
         * 获取第一个职业身份
         */
        public Identity getPrimaryProfession() {
            List<Identity> professions = getIdentitiesByCategory("PROFESSION");
            return professions.isEmpty() ? null : professions.get(0);
        }

        /**
         * 获取职业名称（向后兼容）
         */
        public String professionName() {
            Identity primary = getPrimaryProfession();
            return primary != null ? primary.getName() : null;
        }

        /**
         * 获取职业阶段（向后兼容）
         */
        public String careerStageName() {
            Identity primary = getPrimaryProfession();
            return primary != null ? primary.getCareerStage() : null;
        }

        /**
         * 获取专业技能（向后兼容）
         */
        public List<String> professionalSkills() {
            Identity primary = getPrimaryProfession();
            return primary != null ? primary.getKeywords() : List.of();
        }
    }

    /**
     * 身份数据
     */
    class Identity {
        private String name;
        private String displayName;
        private String category;
        private double confidence;
        private List<String> keywords;
        private String careerStage;

        public Identity(String name, String displayName, String category, double confidence,
                       List<String> keywords, String careerStage) {
            this.name = name;
            this.displayName = displayName;
            this.category = category;
            this.confidence = confidence;
            this.keywords = keywords;
            this.careerStage = careerStage;
        }

        public String getName() {
            return name;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getCategory() {
            return category;
        }

        public double getConfidence() {
            return confidence;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public String getCareerStage() {
            return careerStage;
        }
    }
}
