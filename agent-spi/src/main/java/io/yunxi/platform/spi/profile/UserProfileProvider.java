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
 * @version 2.0.0
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
     * 用户画像数据，聚合多个身份维度、个人上下文、社会关系等结构化信息。
     * <p>
     * 由 {@link UserProfileProvider#getProfile(String)} 返回，供上层进行个性化推理；
     * 其中话题由问题内容决定，不由身份决定。内置多个便捷方法（如按类别取身份、
     * 取职业身份）以兼容历史调用方式。
     * </p>
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
         * 获取指定类别的身份列表。
         *
         * @param category 身份类别（如 {@code PROFESSION}）
         * @return 该类别下的身份列表，若未配置身份则返回空列表
         */
        public List<Identity> getIdentitiesByCategory(String category) {
            return identities == null ? List.of()
                    : identities.stream()
                            .filter(id -> category.equals(id.getCategory()))
                            .toList();
        }

        /**
         * 获取第一个职业身份（类别为 {@code PROFESSION} 的首条身份）。
         *
         * @return 职业身份，若不存在则返回 {@code null}
         */
        public Identity getPrimaryProfession() {
            List<Identity> professions = getIdentitiesByCategory("PROFESSION");
            return professions.isEmpty() ? null : professions.get(0);
        }

        /**
         * 获取职业名称（向后兼容，等价于首个职业身份的 {@code name}）。
         *
         * @return 职业名称，若无职业身份则返回 {@code null}
         */
        public String professionName() {
            Identity primary = getPrimaryProfession();
            return primary != null ? primary.getName() : null;
        }

        /**
         * 获取职业阶段（向后兼容，等价于首个职业身份的 {@code careerStage}）。
         *
         * @return 职业阶段，若无职业身份则返回 {@code null}
         */
        public String careerStageName() {
            Identity primary = getPrimaryProfession();
            return primary != null ? primary.getCareerStage() : null;
        }

        /**
         * 获取专业技能（向后兼容，等价于首个职业身份的 {@code keywords}）。
         *
         * @return 技能关键词列表，若无职业身份则返回空列表
         */
        public List<String> professionalSkills() {
            Identity primary = getPrimaryProfession();
            return primary != null ? primary.getKeywords() : List.of();
        }
    }

    /**
     * 身份数据，描述用户在某一个维度上的身份标签及其置信度与画像关键词。
     * <p>
     * {@code category} 用于区分身份类别（如 {@code PROFESSION} 职业），
     * {@code confidence} 表示画像对该身份的置信程度，{@code keywords} 携带职业相关技能标签。
     * </p>
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
