package io.yunxi.platform.business.nutrition.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.framework.profile.CareerStageRegistry;
import io.yunxi.platform.framework.profile.ConceptRegistry;
import io.yunxi.platform.framework.profile.ProfessionRegistry;
import io.yunxi.platform.spi.profile.UserProfileProvider;

/**
 * ProfessionExtractionService 集成测试
 * 
 * 测试用户身份识别与画像演进服务的核心功能
 */
@ExtendWith(MockitoExtension.class)
class ProfessionExtractionServiceTest {

    @Mock
    private ProfessionRegistry professionRegistry;

    @Mock
    private CareerStageRegistry careerStageRegistry;

    @Mock
    private ConceptRegistry conceptRegistry;

    private ProfessionExtractionService service;

    @BeforeEach
    void setUp() {
        service = new ProfessionExtractionService();
        // 使用反射注入依赖
        try {
            var professionRegistryField = ProfessionExtractionService.class.getDeclaredField("professionRegistry");
            professionRegistryField.setAccessible(true);
            professionRegistryField.set(service, professionRegistry);

            var careerStageRegistryField = ProfessionExtractionService.class.getDeclaredField("careerStageRegistry");
            careerStageRegistryField.setAccessible(true);
            careerStageRegistryField.set(service, careerStageRegistry);

            var conceptRegistryField = ProfessionExtractionService.class.getDeclaredField("conceptRegistry");
            conceptRegistryField.setAccessible(true);
            conceptRegistryField.set(service, conceptRegistry);
        } catch (Exception e) {
            fail("Failed to inject dependencies: " + e.getMessage());
        }
    }

    @Test
    void testEvolveWithEmptyConversation() {
        // 准备测试数据
        UserProfileProvider.UserProfile currentProfile = createTestProfile("test-user");
        List<Msg> emptyConversation = Collections.emptyList();

        // 执行测试
        UserProfileProvider.UserProfile result = service.evolve(currentProfile, emptyConversation);

        // 验证结果
        assertNotNull(result);
        assertEquals(currentProfile.getUserId(), result.getUserId());
        assertEquals(currentProfile.getIdentities().size(), result.getIdentities().size());
    }

    @Test
    void testEvolveWithNewIdentityDetection() {
        // 准备测试数据
        UserProfileProvider.UserProfile currentProfile = null;
        List<Msg> conversation = Arrays.asList(
                createMessage("你好，我是一名营养师，专门从事儿童营养咨询"),
                createMessage("我有5年的从业经验"));

        // 设置mock行为
        when(conceptRegistry.detectIdentities(anyString())).thenReturn(Arrays.asList("nutritionist"));
        when(conceptRegistry.detectDomains(anyString())).thenReturn(Collections.singletonMap("nutrition", 0.8));
        when(conceptRegistry.getCategory("nutritionist")).thenReturn("PROFESSION");
        when(conceptRegistry.getDisplayName("nutritionist")).thenReturn("营养师");
        when(conceptRegistry.getDomain("nutritionist")).thenReturn("nutrition");
        when(careerStageRegistry.fromExperienceYears(5)).thenReturn("中级");

        // 执行测试
        UserProfileProvider.UserProfile result = service.evolve(currentProfile, conversation);

        // 验证结果
        assertNotNull(result);
        assertEquals(1, result.getIdentities().size());
        UserProfileProvider.Identity nutritionistIdentity = result.getIdentities().get(0);
        assertEquals("nutritionist", nutritionistIdentity.getName());
        assertEquals("营养师", nutritionistIdentity.getDisplayName());
        assertEquals("PROFESSION", nutritionistIdentity.getCategory());
        assertEquals("中级", nutritionistIdentity.getCareerStage());
    }

    @Test
    void testEvolveWithExistingIdentityEnhancement() {
        // 准备测试数据
        UserProfileProvider.UserProfile currentProfile = createTestProfile("test-user");
        List<Msg> conversation = Arrays.asList(
                createMessage("作为营养师，我今天又处理了一些糖尿病患者的案例"),
                createMessage("已经从业8年了"));

        // 设置mock行为
        when(conceptRegistry.detectIdentities(anyString())).thenReturn(Arrays.asList("nutritionist"));
        when(conceptRegistry.detectDomains(anyString())).thenReturn(Collections.singletonMap("nutrition", 0.7));
        when(conceptRegistry.getCategory("nutritionist")).thenReturn("PROFESSION");
        when(conceptRegistry.getDisplayName("nutritionist")).thenReturn("营养师");
        when(careerStageRegistry.fromExperienceYears(8)).thenReturn("高级");

        // 获取初始置信度
        double initialConfidence = currentProfile.getIdentities().get(0).getConfidence();

        // 执行测试
        UserProfileProvider.UserProfile result = service.evolve(currentProfile, conversation);

        // 验证结果
        assertEquals(1, result.getIdentities().size());
        UserProfileProvider.Identity updatedIdentity = result.getIdentities().get(0);
        assertEquals("nutritionist", updatedIdentity.getName());
        assertEquals("高级", updatedIdentity.getCareerStage());
        // 置信度应该增强
        assertTrue(updatedIdentity.getConfidence() > initialConfidence);
        assertTrue(updatedIdentity.getConfidence() <= 1.0);
    }

    @Test
    void testConsolidateWithHighConfidenceIdentities() {
        // 准备测试数据
        String userId = "test-user";
        UserProfileProvider.Identity highConfidenceIdentity = createTestIdentity("nutritionist", "营养师", "PROFESSION",
                0.9);
        UserProfileProvider.UserProfile profile = createTestProfile(userId);
        List<UserProfileProvider.Identity> identities = new ArrayList<>();
        identities.add(highConfidenceIdentity);

        // 创建高置信度用户画像
        UserProfileProvider.UserProfile highConfidenceProfile = new UserProfileProvider.UserProfile(
                userId, identities,
                profile.getPersonalContext(),
                profile.getSocialRelations(),
                System.currentTimeMillis());

        // 执行整理
        UserProfileProvider.UserProfile result = service.consolidate(highConfidenceProfile);

        // 验证高置信度身份被保留
        assertEquals(1, result.getIdentities().size());
        assertNotNull(result.getIdentities().get(0));
    }

    @Test
    void testConsolidateWithLowConfidenceIdentities() {
        // 准备测试数据
        String userId = "test-user";
        UserProfileProvider.Identity lowConfidenceIdentity = createTestIdentity("transient", "临时身份", "INTEREST", 0.2);
        UserProfileProvider.UserProfile profile = createTestProfile(userId);
        List<UserProfileProvider.Identity> identities = new ArrayList<>();
        identities.add(lowConfidenceIdentity);

        // 创建低置信度用户画像
        UserProfileProvider.UserProfile lowConfidenceProfile = new UserProfileProvider.UserProfile(
                userId, identities,
                profile.getPersonalContext(),
                profile.getSocialRelations(),
                System.currentTimeMillis());

        // 执行整理
        UserProfileProvider.UserProfile result = service.consolidate(lowConfidenceProfile);

        // 验证低置信度身份被移除
        assertEquals(0, result.getIdentities().size());
    }

    @Test
    void testProfileCaching() {
        // 测试缓存功能
        String userId = "cache-test-user";
        UserProfileProvider.UserProfile profile = createTestProfile(userId);

        // 更新缓存
        service.updateProfile(userId, profile);

        // 从缓存获取
        UserProfileProvider.UserProfile cachedProfile = service.getProfile(userId);

        // 验证缓存功能
        assertNotNull(cachedProfile);
        assertEquals(userId, cachedProfile.getUserId());
    }

    @Test
    void testPersonalContextExtraction() {
        // 测试个人上下文提取功能
        List<Msg> conversation = Arrays.asList(
                createMessage("我爸有高血压需要管理"),
                createMessage("孩子在上幼儿园，饮食需要特别关注"));

        // 设置mock行为
        when(conceptRegistry.detectIdentities(anyString())).thenReturn(Arrays.asList("nutritionist"));
        when(conceptRegistry.detectDomains(anyString())).thenReturn(Collections.singletonMap("nutrition", 0.6));
        when(conceptRegistry.getCategory("nutritionist")).thenReturn("PROFESSION");
        when(conceptRegistry.getDisplayName("nutritionist")).thenReturn("营养师");

        // 执行测试
        UserProfileProvider.UserProfile result = service.evolve(null, conversation);

        // 验证个人上下文提取
        assertNotNull(result.getPersonalContext());
        assertTrue(result.getPersonalContext().containsKey("family_elder"));
        assertTrue(result.getPersonalContext().containsKey("family_children"));
        assertEquals("有老人需要照护", result.getPersonalContext().get("family_elder"));
        assertEquals("有子女", result.getPersonalContext().get("family_children"));
    }

    @Test
    void testExtractExperienceYears() {
        // 通过集成测试间接验证工作经验提取
        List<Msg> conversation = Arrays.asList(
                createMessage("我从事营养咨询工作已经3年了"),
                createMessage("主要处理家庭营养问题"));

        // 设置mock行为
        when(conceptRegistry.detectIdentities(anyString())).thenReturn(Arrays.asList("nutritionist"));
        when(conceptRegistry.detectDomains(anyString())).thenReturn(Collections.singletonMap("nutrition", 0.5));
        when(conceptRegistry.getCategory("nutritionist")).thenReturn("PROFESSION");
        when(conceptRegistry.getDisplayName("nutritionist")).thenReturn("营养师");
        when(careerStageRegistry.fromExperienceYears(3)).thenReturn("初级");

        // 执行测试
        UserProfileProvider.UserProfile result = service.evolve(null, conversation);

        // 验证职业阶段推断
        assertEquals(1, result.getIdentities().size());
        assertEquals("初级", result.getIdentities().get(0).getCareerStage());
    }

    // 辅助方法：创建测试消息
    private Msg createMessage(String text) {
        return Msg.builder()
                .textContent(text)
                .build();
    }

    // 辅助方法：创建测试身份
    private UserProfileProvider.Identity createTestIdentity(String name, String displayName, String category,
            double confidence) {
        return new UserProfileProvider.Identity(
                name, displayName, category, confidence,
                Arrays.asList("关键词1", "关键词2"), "中级");
    }

    // 辅助方法：创建测试用户画像
    private UserProfileProvider.UserProfile createTestProfile(String userId) {
        UserProfileProvider.Identity identity = createTestIdentity("nutritionist", "营养师", "PROFESSION", 0.7);
        Map<String, String> personalContext = new HashMap<>();
        personalContext.put("diet_type", "关注均衡饮食");
        Map<String, String> socialRelations = new HashMap<>();
        socialRelations.put("peer_group", "营养师协会");

        return new UserProfileProvider.UserProfile(
                userId, Arrays.asList(identity),
                personalContext, socialRelations,
                System.currentTimeMillis());
    }
}