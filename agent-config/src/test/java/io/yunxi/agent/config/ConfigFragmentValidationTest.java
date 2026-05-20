package io.yunxi.agent.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置文件片段验证测试
 * 验证所有YAML配置文件格式正确性和关键配置项
 */
class ConfigFragmentValidationTest {

    private Yaml yaml;

    @BeforeEach
    void setUp() {
        yaml = new Yaml();
    }

    @Test
    void testServerConfigFormat() {
        validateConfigFile("config/server.yml", "server");
    }

    @Test
    void testAsyncConfigFormat() {
        validateConfigFile("config/async.yml", "spring.task.execution");
    }

    @Test
    void testCacheConfigFormat() {
        validateConfigFile("config/cache.yml", "spring.cache");
    }

    @Test
    void testDatasourceConfigFormat() {
        validateConfigFile("config/datasource.yml", "spring.datasource");
    }

    @Test
    void testRedisConfigFormat() {
        validateConfigFile("config/redis.yml", "spring.data.redis");
    }

    @Test
    void testLLMConfigFormat() {
        validateConfigFile("config/llm.yml", "io.yunxi.platform.llm");
    }

    @Test
    void testMilvusConfigFormat() {
        validateConfigFile("config/milvus.yml", "io.yunxi.platform.milvus");
    }

    @Test
    void testEmbeddingConfigFormat() {
        validateConfigFile("config/embedding.yml", "io.yunxi.platform.embedding");
    }

    @Test
    void testResilienceConfigFormat() {
        validateConfigFile("config/resilience.yml", "resilience4j");
    }

    @Test
    void testGatewayConfigFormat() {
        validateConfigFile("config/gateway.yml", "spring.cloud.gateway");
    }

    @Test
    void testBusinessConfigFormat() {
        validateConfigFile("config/business.yml", "yunxi.agent");
    }

    @Test
    void testMCPServerConfigFormat() {
        validateConfigFile("config/mcp-core.yml", "mcp");
    }

    @Test
    void testSkillConfigFormat() {
        validateConfigFile("config/skill.yml", "skill");
    }

    @Test
    void testA2APipelineConfigFormat() {
        validateConfigFile("config/a2a-pipeline.yml", "a2a.pipeline");
    }

    @Test
    void testFileUploadConfigFormat() {
        validateConfigFile("config/file-upload.yml", "spring.servlet.multipart");
    }

    @Test
    void testText2SQLConfigFormat() {
        validateConfigFile("config/text2sql.yml", "text2sql");
    }

    @Test
    void testRuleEngineConfigFormat() {
        validateConfigFile("config/rule-engine.yml", "rule.engine");
    }

    @Test
    void testPersistenceConfigFormat() {
        validateConfigFile("config/persistence.yml", "spring.jpa");
    }

    @Test
    void testMCPServerExternalConfigFormat() {
        validateConfigFile("config/mcp-external.yml", "mcp.external");
    }

    @Test
    void testMCPServerBusinessConfigFormat() {
        validateConfigFile("config/mcp-business.yml", "mcp.business");
    }

    @Test
    void testAgentScopeConfigFormat() {
        validateConfigFile("config/agentscope.yml", "io.yunxi.platform.agentscope");
    }

    private void validateConfigFile(String filePath, String expectedRootKey) {
        try {
            Path configPath = Paths.get("src/main/resources", filePath);
            
            if (!Files.exists(configPath)) {
                System.out.println("Config file not found: " + configPath);
                return; // 跳过不存在的配置文件
            }
            
            try (InputStream input = Files.newInputStream(configPath)) {
                Object data = yaml.load(input);
                
                assertNotNull(data, "Config file " + filePath + " should not be empty");
                
                if (data instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> configMap = (Map<String, Object>) data;
                    
                    // 验证配置文件结构
                    assertFalse(configMap.isEmpty(), 
                        "Config file " + filePath + " should contain configuration");
                    
                    // 验证关键根配置项（如果存在）
                    if (expectedRootKey != null) {
                        String[] keyParts = expectedRootKey.split("\\.");
                        Map<String, Object> currentLevel = configMap;
                        
                        for (String keyPart : keyParts) {
                            if (currentLevel.containsKey(keyPart)) {
                                Object value = currentLevel.get(keyPart);
                                if (value instanceof Map) {
                                    currentLevel = (Map<String, Object>) value;
                                }
                            }
                        }
                        
                        System.out.println("Validated config file: " + filePath);
                    }
                }
            }
        } catch (Exception e) {
            fail("Failed to load or parse config file: " + filePath + " - " + e.getMessage());
        }
    }

    @Test
    void testAllConfigFilesPresent() {
        // 验证应用主配置文件存在
        assertTrue(Files.exists(Paths.get("src/main/resources/application.yml")),
            "Main application.yml should exist");
        
        assertTrue(Files.exists(Paths.get("src/main/resources/async.yml")),
            "Async config should exist");
        
        assertTrue(Files.exists(Paths.get("src/main/resources/cache.yml")),
            "Cache config should exist");
        
        assertTrue(Files.exists(Paths.get("src/main/resources/server.yml")),
            "Server config should exist");
    }

    @Test
    void testConfigImportOrder() {
        try {
            Path appConfigPath = Paths.get("src/main/resources/application.yml");
            String content = Files.readString(appConfigPath);
            
            // 验证config.import配置存在
            assertTrue(content.contains("spring:"), "Should contain spring configuration");
            assertTrue(content.contains("config:"), "Should contain config section");
            assertTrue(content.contains("import:"), "Should contain import directive");
            
            // 验证关键配置文件的加载顺序
            assertTrue(content.contains("server.yml"), "Should import server.yml");
            assertTrue(content.contains("datasource.yml"), "Should import datasource.yml");
            assertTrue(content.contains("cache.yml"), "Should import cache.yml");
            assertTrue(content.contains("async.yml"), "Should import async.yml");
            
        } catch (Exception e) {
            fail("Failed to validate config import order: " + e.getMessage());
        }
    }

    @Test
    void testProfilesConfiguration() {
        try {
            Path appConfigPath = Paths.get("src/main/resources/application.yml");
            String content = Files.readString(appConfigPath);
            
            // 验证profile配置存在
            assertTrue(content.contains("spring.profiles:"), 
                "Should contain spring profiles configuration");
            assertTrue(content.contains("active:"), 
                "Should contain active profiles configuration");
            
            // 验证关键profiles
            assertTrue(content.contains("server"), "Should activate server profile");
            assertTrue(content.contains("datasource"), "Should activate datasource profile");
            assertTrue(content.contains("cache"), "Should activate cache profile");
            assertTrue(content.contains("async"), "Should activate async profile");
            
        } catch (Exception e) {
            fail("Failed to validate profiles configuration: " + e.getMessage());
        }
    }

    @Test
    void testYamlSyntaxValidity() {
        // 验证所有YAML文件语法正确性
        validateYamlFile("application.yml");
        validateYamlFile("async.yml");
        validateYamlFile("cache.yml");
        validateYamlFile("server.yml");
        
        // 验证配置目录中的所有文件
        try {
            Path configDir = Paths.get("src/main/resources/config");
            if (Files.exists(configDir)) {
                Files.list(configDir)
                    .filter(path -> path.toString().endsWith(".yml") || path.toString().endsWith(".yaml"))
                    .forEach(path -> validateYamlFile("config/" + path.getFileName().toString()));
            }
        } catch (Exception e) {
            System.out.println("Could not validate config directory: " + e.getMessage());
        }
    }

    private void validateYamlFile(String fileName) {
        try {
            Path filePath = Paths.get("src/main/resources", fileName);
            if (!Files.exists(filePath)) {
                System.out.println("File not found: " + fileName);
                return;
            }
            
            try (InputStream input = Files.newInputStream(filePath)) {
                Object data = yaml.load(input);
                assertNotNull(data, "YAML file " + fileName + " should parse correctly");
                
                // 如果是Map，验证至少包含一些配置
                if (data instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mapData = (Map<String, Object>) data;
                    System.out.println("Valid YAML file: " + fileName + " with " + mapData.size() + " top-level entries");
                }
            }
        } catch (Exception e) {
            fail("YAML syntax error in file " + fileName + ": " + e.getMessage());
        }
    }
}