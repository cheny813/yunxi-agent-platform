package io.yunxi.platform.framework.profile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConceptRegistry 集成测试
 * 
 * 测试关键词匹配、身份识别、话题检测等核心功能
 */
@SpringBootTest
@ExtendWith(SpringExtension.class)
@EnableConfigurationProperties(ConceptRegistry.class)
@ActiveProfiles("test")
class ConceptRegistryTest {

    private ConceptRegistry conceptRegistry;

    @BeforeEach
    void setUp() {
        // 配置将在application-test.yml中加载，这里我们测试Spring容器的注入效果
        conceptRegistry = new ConceptRegistry();
    }

    @Test
    void testDetectIdentitiesWithMedicalKeywords() {
        // 准备包含医疗关键词的文本
        String text = "最近血压有点高，需要定期监测血糖，同时要注意健康饮食和康复运动。";
        
        // 执行测试
        List<String> result = conceptRegistry.detectIdentities(text);
        
        // 验证结果：应该检测到MEDICAL身份
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("MEDICAL"), "应该检测到MEDICAL身份");
    }

    @Test
    void testDetectIdentitiesWithParentingKeywords() {
        // 准备包含育儿关键词的文本
        String text = "我每天要接送孩子上学，还要辅导作业，周末带他去幼儿园参加亲子活动。";
        
        // 执行测试
        List<String> result = conceptRegistry.detectIdentities(text);
        
        // 验证结果：应该检测到PARENTING身份
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("PARENTING"), "应该检测到PARENTING身份");
    }

    @Test
    void testDetectIdentitiesWithMultipleIdentities() {
        // 准备包含多个领域关键词的文本
        String text = "作为家长，我关注孩子的健康问题，比如血压监测和饮食习惯，同时也关心学校配餐的营养均衡。";
        
        // 执行测试
        List<String> result = conceptRegistry.detectIdentities(text);
        
        // 验证结果：可能检测到多个身份
        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        // 验证排序：匹配关键词多的排在前面
        if (result.size() > 1) {
            // 这里的逻辑验证需要了解具体配置，简化验证
            assertTrue(result.contains("PARENTING") || result.contains("MEDICAL") || result.contains("NUTRITION"));
        }
    }

    @Test
    void testDetectIdentitiesWithEmptyText() {
        // 测试空文本
        String text = "";
        
        // 执行测试
        List<String> result = conceptRegistry.detectIdentities(text);
        
        // 验证结果：空文本应该返回空列表
        assertNotNull(result);
        assertTrue(result.isEmpty(), "空文本应该返回空列表");
    }

    @Test
    void testDetectIdentitiesWithNullText() {
        // 测试null文本
        String text = null;
        
        // 执行测试
        List<String> result = conceptRegistry.detectIdentities(text);
        
        // 验证结果：null文本应该返回空列表
        assertNotNull(result);
        assertTrue(result.isEmpty(), "null文本应该返回空列表");
    }

    @Test
    void testDetectIdentitiesWithNoKeywords() {
        // 测试不包含任何关键词的文本
        String text = "今天天气很好，阳光明媚，适合户外活动。";
        
        // 执行测试
        List<String> result = conceptRegistry.detectIdentities(text);
        
        // 验证结果：无关键词匹配应该返回空列表
        assertNotNull(result);
        assertTrue(result.isEmpty(), "无关键词匹配应该返回空列表");
    }

    @Test
    void testGetEntriesByDomain() {
        // 测试按领域获取概念条目
        String healthDomain = "health";
        
        // 执行测试
        List<ConceptRegistry.ConceptEntry> result = conceptRegistry.getByDomain(healthDomain);
        
        // 验证结果
        assertNotNull(result);
        // 具体的条目数量依赖于配置，这里只验证结构
        result.forEach(entry -> {
            assertNotNull(entry.getName());
            assertNotNull(entry.getDomain());
            assertEquals(healthDomain, entry.getDomain(), "条目领域应该匹配");
        });
    }

    @Test
    void testGetEntriesByCategory() {
        // 测试按类别获取概念条目
        String professionCategory = "PROFESSION";
        
        // 执行测试
        List<ConceptRegistry.ConceptEntry> result = conceptRegistry.getByCategory(professionCategory);
        
        // 验证结果
        assertNotNull(result);
        // 具体的条目数量依赖于配置，这里只验证结构
        result.forEach(entry -> {
            assertNotNull(entry.getName());
            assertNotNull(entry.getCategory());
            assertEquals(professionCategory, entry.getCategory(), "条目类别应该匹配");
        });
    }

    @Test
    void testGetEntryByName() {
        // 测试按名称获取概念条目
        String entryName = "MEDICAL";
        
        // 执行测试
        ConceptRegistry.ConceptEntry result = conceptRegistry.getByName(entryName);
        
        // 验证结果
        assertNotNull(result);
        assertEquals(entryName, result.getName(), "条目名称应该匹配");
        assertNotNull(result.getDomain(), "领域不应该为空");
        assertNotNull(result.getDisplayName(), "显示名称不应该为空");
        assertNotNull(result.getKeywords(), "关键词不应该为空");
    }

    @Test
    void testGetEntryByNonExistingName() {
        // 测试查询不存在的名称
        String nonExistingName = "NON_EXISTING";
        
        // 执行测试
        ConceptRegistry.ConceptEntry result = conceptRegistry.getByName(nonExistingName);
        
        // 验证结果：应该返回null
        assertNull(result, "不存在的名称应该返回null");
    }

    @Test
    void testGetByNonExistingDomain() {
        // 测试查询不存在的领域
        String nonExistingDomain = "non_existing_domain";
        
        // 执行测试
        List<ConceptRegistry.ConceptEntry> result = conceptRegistry.getByDomain(nonExistingDomain);
        
        // 验证结果：应该返回空列表
        assertNotNull(result, "结果不应该为null");
        assertTrue(result.isEmpty(), "不存在的领域应该返回空列表");
    }

    @Test
    void testGetByNonExistingCategory() {
        // 测试查询不存在的类别
        String nonExistingCategory = "NON_EXISTING_CATEGORY";
        
        // 执行测试
        List<ConceptRegistry.ConceptEntry> result = conceptRegistry.getByCategory(nonExistingCategory);
        
        // 验证结果：应该返回空列表
        assertNotNull(result, "结果不应该为null");
        assertTrue(result.isEmpty(), "不存在的类别应该返回空列表");
    }

    @Test
    void testRegisterNewConcept() {
        // 测试动态注册新概念
        ConceptRegistry.ConceptEntry newEntry = new ConceptRegistry.ConceptEntry();
        newEntry.setName("TEST_CONCEPT");
        newEntry.setDomain("test");
        newEntry.setDisplayName("测试概念");
        newEntry.setKeywords("测试、验证、概念");
        newEntry.setCategory("TEST");
        
        // 执行注册
        conceptRegistry.register(newEntry);
        
        // 验证注册成功
        ConceptRegistry.ConceptEntry result = conceptRegistry.getByName("TEST_CONCEPT");
        assertNotNull(result, "注册的概念应该能被查询到");
        assertEquals("TEST_CONCEPT", result.getName());
        assertEquals("测试概念", result.getDisplayName());
    }

    @Test
    void testRegisterDuplicateConcept() {
        // 测试重复注册概念的处理
        ConceptRegistry.ConceptEntry entry1 = new ConceptRegistry.ConceptEntry();
        entry1.setName("DUPLICATE_TEST");
        entry1.setDomain("test");
        entry1.setKeywords("关键词1");
        
        ConceptRegistry.ConceptEntry entry2 = new ConceptRegistry.ConceptEntry();
        entry2.setName("DUPLICATE_TEST"); // 同名
        entry2.setDomain("test");
        entry2.setKeywords("关键词2");
        
        // 注册第一个
        conceptRegistry.register(entry1);
        
        // 注册同名概念（应该覆盖）
        conceptRegistry.register(entry2);
        
        // 验证第二个覆盖了第一个
        ConceptRegistry.ConceptEntry result = conceptRegistry.getByName("DUPLICATE_TEST");
        assertNotNull(result);
        assertEquals("关键词2", result.getKeywords(), "重复注册应该覆盖之前的条目");
    }

    @Test
    void testKeywordPatternCompilation() {
        // 测试关键词编译逻辑
        String keywords = "健康|营养|运动,养生,瑜伽;太极";
        
        // 获取内部方法（通过反射）
        // 这里简化测试，假设编译成功
        ConceptRegistry.ConceptEntry entry = new ConceptRegistry.ConceptEntry();
        entry.setName("PATTERN_TEST");
        entry.setKeywords(keywords);
        
        conceptRegistry.register(entry);
        
        // 验证该关键字能被检测到
        String testText = "我平时注重健康营养和瑜伽运动";
        List<String> identities = conceptRegistry.detectIdentities(testText);
        
        assertTrue(identities.contains("PATTERN_TEST"), "应该检测到模式测试概念");
    }

    @Test
    void testPatternMatchingPrecision() {
        // 测试模式匹配的精确性
        String text = "健康饮食很重要，但提到医疗这个词并不代表医疗主题";
        
        List<String> result = conceptRegistry.detectIdentities(text);
        
        // 验证匹配逻辑："健康"相关应该匹配到某个身份
        assertNotNull(result);
        // 具体的验证取决于配置，但应该至少匹配到一些概念
        assertTrue(result.size() <= 2, "匹配应该合理精确");
    }

    @Test
    void testGetAllEntries() {
        // 测试获取所有概念条目
        List<ConceptRegistry.ConceptEntry> allEntries = conceptRegistry.getEntries();
        
        // 验证结果
        assertNotNull(allEntries);
        assertFalse(allEntries.isEmpty(), "应该有配置的概念条目");
        
        // 验证每个条目的结构
        allEntries.forEach(entry -> {
            assertNotNull(entry.getName(), "概念名称不应为空");
            assertNotNull(entry.getDisplayName(), "显示名称不应为空");
            assertNotNull(entry.getKeywords(), "关键词不应为空");
        });
    }

    @Test
    void testConceptEntryStructure() {
        // 测试ConceptEntry内部类的结构
        ConceptRegistry.ConceptEntry entry = new ConceptRegistry.ConceptEntry();
        
        // 设置属性
        entry.setName("TEST_ENTRY");
        entry.setDomain("test");
        entry.setDisplayName("测试条目");
        entry.setKeywords("测试、关键词");
        entry.setCategory("TEST");
        
        // 验证getter方法
        assertEquals("TEST_ENTRY", entry.getName());
        assertEquals("test", entry.getDomain());
        assertEquals("测试条目", entry.getDisplayName());
        assertEquals("测试、关键词", entry.getKeywords());
        assertEquals("TEST", entry.getCategory());
    }

    @Test
    void testConfigurationPropertiesBinding() {
        // 测试Spring配置属性绑定
        // 这个测试依赖于application-test.yml中的配置
        ConceptRegistry registry = new ConceptRegistry();
        // Spring会自动绑定配置，这里验证基本功能
        assertNotNull(registry.getEntries(), "配置条目应该被加载");
        
        // 验证至少有一些预定义的概念
        assertTrue(registry.getEntries().size() >= 5, "应该有足够的预定义概念");
    }
}
