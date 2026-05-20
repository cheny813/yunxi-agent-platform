package io.yunxi.platform.business.nutrition.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.framework.profile.CareerStageRegistry;
import io.yunxi.platform.framework.profile.ConceptRegistry;
import io.yunxi.platform.framework.profile.ProfessionRegistry;
import io.yunxi.platform.framework.spi.UserProfileEvolver;
import io.yunxi.platform.spi.profile.UserProfileProvider.Identity;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户身份识别与画像演进服务
 *
 * <p>
 * 从对话中提取用户的身份信息，构建多维用户画像。
 * 实现 {@link UserProfileEvolver} 接口，支持增量合并和定期整理。
 * </p>
 *
 * <p>
 * 身份检测基于 ConceptRegistry 中的关键词规则，无需硬编码映射。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 3.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class ProfessionExtractionService implements UserProfileEvolver {

    /** 置信度阈值，低于此值的身份将被移除 */
    private static final double CONFIDENCE_THRESHOLD = 0.3;
    /** 置信度衰减系数，用于定期整理时降低置信度 */
    private static final double CONFIDENCE_DECAY = 0.95;
    /** 用户画像缓存过期时间（毫秒） */
    private static final long PROFILE_CACHE_TTL = 24 * 60 * 60 * 1000L;

    /** 职业注册表，提供职业关键词匹配 */
    @Autowired
    private ProfessionRegistry professionRegistry;

    /** 职业阶段注册表，根据从业年限推断职业阶段 */
    @Autowired
    private CareerStageRegistry careerStageRegistry;

    /** 概念注册表，提供身份和领域关键词检测 */
    @Autowired
    private ConceptRegistry conceptRegistry;

    /** 用户画像本地缓存 */
    private final ConcurrentHashMap<String, UserProfile> profileCache = new ConcurrentHashMap<>();

    // ==================== UserProfileEvolver 实现 ====================

    /**
     * 基于对话内容演进用户画像，增量合并身份信息和个人上下文
     *
     * @param current      当前用户画像，可为 null
     * @param conversation 对话消息列表
     * @return 演进后的用户画像
     */
    @Override
    public UserProfile evolve(UserProfile current, List<Msg> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return current;
        }

        // 合并对话文本
        StringBuilder fullText = new StringBuilder();
        for (Msg msg : conversation) {
            if (msg.getTextContent() != null) {
                fullText.append(msg.getTextContent()).append("\n");
            }
        }
        String text = fullText.toString();

        // 从 ConceptRegistry 检测所有身份
        List<String> detectedNames = conceptRegistry.detectIdentities(text);
        Map<String, Double> domainScores = conceptRegistry.detectDomains(text);

        // 构建新检测到的身份列表
        Map<String, Identity> identityMap = new LinkedHashMap<>();
        if (current != null && current.getIdentities() != null) {
            for (Identity id : current.getIdentities()) {
                identityMap.put(id.getName(), id);
            }
        }

        for (String name : detectedNames) {
            String category = conceptRegistry.getCategory(name);
            String displayName = conceptRegistry.getDisplayName(name);

            if (category == null)
                category = "INTEREST";

            Identity existing = identityMap.get(name);
            if (existing != null) {
                // 已有身份 → 增强置信度
                double newConfidence = Math.min(1.0, existing.getConfidence() + 0.1);
                identityMap.put(name, new Identity(
                        name, displayName, category, newConfidence,
                        existing.getKeywords(), existing.getCareerStage()));
            } else {
                // 新身份 → 初始置信度
                double initialConf = domainScores.getOrDefault(conceptRegistry.getDomain(name), 0.5);
                identityMap.put(name, new Identity(
                        name, displayName, category, initialConf,
                        extractSkillsForIdentity(text, name), null));
            }
        }

        // 为 PROFESSION 类别推断职业阶段
        int experienceYears = extractExperienceYears(text);
        if (experienceYears > 0) {
            String stage = careerStageRegistry.fromExperienceYears(experienceYears);
            for (Map.Entry<String, Identity> entry : identityMap.entrySet()) {
                if ("PROFESSION".equals(entry.getValue().getCategory()) && entry.getValue().getCareerStage() == null) {
                    Identity id = entry.getValue();
                    identityMap.put(entry.getKey(), new Identity(
                            id.getName(), id.getDisplayName(), id.getCategory(), id.getConfidence(),
                            id.getKeywords(), stage));
                }
            }
        }

        // 构建新的 UserProfile
        Map<String, String> personalContext = current != null && current.getPersonalContext() != null
                ? new HashMap<>(current.getPersonalContext())
                : new HashMap<>();
        Map<String, String> socialRelations = current != null && current.getSocialRelations() != null
                ? new HashMap<>(current.getSocialRelations())
                : new HashMap<>();

        // 从对话中提取个人上下文
        extractPersonalContext(text, personalContext);

        String userId = current != null ? current.getUserId() : null;
        UserProfile result = new UserProfile(
                userId,
                new ArrayList<>(identityMap.values()),
                personalContext,
                socialRelations,
                System.currentTimeMillis());

        // 缓存
        if (userId != null) {
            profileCache.put(userId, result);
        }

        log.info("用户画像演进完成: identities={}, personalContext={}", identityMap.size(), personalContext.size());
        return result;
    }

    /**
     * 定期整理用户画像，对低置信度身份进行衰减和清理
     *
     * @param current 当前用户画像
     * @return 整理后的用户画像
     */
    @Override
    public UserProfile consolidate(UserProfile current) {
        if (current == null || current.getIdentities() == null) {
            return current;
        }

        List<Identity> consolidated = new ArrayList<>();
        for (Identity id : current.getIdentities()) {
            // 置信度衰减
            double decayedConf = id.getConfidence() * CONFIDENCE_DECAY;
            if (decayedConf < CONFIDENCE_THRESHOLD) {
                log.debug("身份置信度过低，移除: {} ({})", id.getName(), decayedConf);
                continue;
            }
            consolidated.add(new Identity(
                    id.getName(), id.getDisplayName(), id.getCategory(), decayedConf,
                    id.getKeywords(), id.getCareerStage()));
        }

        // 清理过时的 personalContext（简单策略：保留最近100条）
        Map<String, String> personalContext = current.getPersonalContext() != null
                ? new HashMap<>(current.getPersonalContext())
                : new HashMap<>();
        Map<String, String> socialRelations = current.getSocialRelations() != null
                ? new HashMap<>(current.getSocialRelations())
                : new HashMap<>();

        return new UserProfile(
                current.getUserId(),
                consolidated,
                personalContext,
                socialRelations,
                System.currentTimeMillis());
    }

    // ==================== 向后兼容方法 ====================

    /**
     * 获取用户画像缓存
     *
     * @param userId 用户ID
     * @return 用户画像，不存在则返回 null
     */
    public UserProfile getProfile(String userId) {
        return profileCache.get(userId);
    }

    /**
     * 更新用户画像缓存
     *
     * @param userId  用户ID
     * @param profile 用户画像
     */
    public void updateProfile(String userId, UserProfile profile) {
        profileCache.put(userId, profile);
        log.info("更新用户画像: userId={}, identities={}", userId,
                profile.getIdentities() != null ? profile.getIdentities().size() : 0);
    }

    // ==================== 内部方法 ====================

    /**
     * 从文本中提取从业年限
     *
     * @param text 对话文本
     * @return 从业年限，未匹配则返回 0
     */
    private int extractExperienceYears(String text) {
        if (text.contains("从事") || text.contains("工作") || text.contains("从业")) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(从事|工作|从业)(\\d+)年");
            java.util.regex.Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException e) {
                    log.warn("解析从业年限失败: {}", text);
                }
            }
        }
        return 0;
    }

    /**
     * 提取指定身份的关键词匹配
     *
     * @param text         对话文本
     * @param identityName 身份名称
     * @return 匹配到的关键词列表
     */
    private List<String> extractSkillsForIdentity(String text, String identityName) {
        List<String> skills = new ArrayList<>();
        String keywords = conceptRegistry.getAllByName().get(identityName) != null
                ? conceptRegistry.getAllByName().get(identityName).getKeywords()
                : null;
        if (keywords != null) {
            for (String keyword : keywords.split("[、,|]")) {
                String trimmed = keyword.trim();
                if (!trimmed.isEmpty() && text.contains(trimmed)) {
                    skills.add(trimmed);
                }
            }
        }
        return skills.stream().distinct().toList();
    }

    /**
     * 从对话文本中提取个人上下文（家庭、健康等信息）
     *
     * @param text    对话文本
     * @param context 待填充的个人上下文 Map
     */
    private void extractPersonalContext(String text, Map<String, String> context) {
        // 家庭相关
        if (text.contains("老人") || text.contains("父母") || text.contains("父亲") || text.contains("母亲")) {
            context.putIfAbsent("family_elder", "有老人需要照护");
        }
        if (text.contains("孩子") || text.contains("子女") || text.contains("上学") || text.contains("幼儿园")) {
            context.putIfAbsent("family_children", "有子女");
        }
        // 健康关注
        if (text.contains("血压") || text.contains("血糖") || text.contains("慢性病")) {
            context.putIfAbsent("health_concern", "关注慢性病管理");
        }
    }

}
