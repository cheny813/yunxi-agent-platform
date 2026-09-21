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
     * 其中话题由问题内容决定，不由身份决定。身份类别为自由标识，由配置或抽取规则定义。
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
         * @param category 身份类别标识（由配置或抽取规则定义，框架不内置行业类别）
         * @return 该类别下的身份列表，若未配置身份则返回空列表
         */
        public List<Identity> getIdentitiesByCategory(String category) {
            return identities == null ? List.of()
                    : identities.stream()
                            .filter(id -> category.equals(id.getCategory()))
                            .toList();
        }

    /**
     * 获取指定类别下的首个身份（按身份列表顺序的第一条）。
     *
     * @param category 身份类别标识
     * @return 该类别下的首个身份，若不存在则返回 {@code null}
     */
    public Identity getPrimary(String category) {
        List<Identity> matches = getIdentitiesByCategory(category);
        return matches.isEmpty() ? null : matches.get(0);
    }
}

    /**
     * 身份数据，描述用户在某一个维度上的身份标签及其置信度与画像关键词。
     * <p>
     * {@code category} 为自由类别标识（由配置或抽取规则定义，框架不内置任何行业类别），
     * {@code confidence} 表示画像对该身份的置信程度，{@code keywords} 携带该身份的标签，
     * {@code attributes} 为任意维度的自由键值（如阶段、等级、归属等），由具体类别自行解释。
     * </p>
     */
    class Identity {
        private String name;
        private String displayName;
        private String category;
        private double confidence;
        private List<String> keywords;
        private Map<String, String> attributes;

        public Identity(String name, String displayName, String category, double confidence,
                       List<String> keywords, Map<String, String> attributes) {
            this.name = name;
            this.displayName = displayName;
            this.category = category;
            this.confidence = confidence;
            this.keywords = keywords;
            this.attributes = attributes != null ? attributes : Map.of();
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

        public Map<String, String> getAttributes() {
            return attributes;
        }
    }
}
