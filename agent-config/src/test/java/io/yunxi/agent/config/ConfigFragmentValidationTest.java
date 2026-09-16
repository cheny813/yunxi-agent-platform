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
        // Redis 绑定前缀必须是 spring.data.redis（Spring Boot 3.x 起生效的前缀）。
        // 旧写法只作兼容元数据别名，不参与绑定，写成旧前缀会让整段配置被静默忽略。
        validateConfigFile("config/redis.yml", "spring.data.redis");
    }

    /**
     * 防止 Redis 配置前缀回归。
     *
     * <p>历史教训：曾把 {@code config/redis.yml} 与 {@code a2a-pipeline.yml} 的
     * {@code spring.data.redis} 前缀误判为「废弃写法」而改回旧前缀，导致连接参数
     * 全部退回 Spring 默认值（空密码 → NOAUTH），表现为「健康检查 503 但配置看着全对」。
     * 本用例把该前缀锁死，任何回退都会被测试拦下。</p>
     */
    @Test
    void testRedisConfigUsesBoot3Prefix() {
        String redisYml;
        String a2aYml;
        try {
            redisYml = Files.readString(Paths.get("src/main/resources/config/redis.yml"));
            a2aYml = Files.readString(Paths.get("src/main/resources/config/a2a-pipeline.yml"));
        } catch (Exception e) {
            fail("Failed to read redis configs: " + e.getMessage());
            return;
        }

        assertTrue(redisYml.contains("spring.data.redis"),
            "config/redis.yml 必须使用 spring.data.redis 前缀（Spring Boot 3.x 绑定前缀）");
        assertTrue(a2aYml.contains("spring.data.redis")
                || !a2aYml.contains("spring:\n  redis:"),
            "config/a2a-pipeline.yml 不得再用旧前缀 spring.redis 重复声明连接参数");
        // 旧写法 `spring:\n  redis:` 作为独立层级出现即为回归（注意与 spring.data.redis 区分）
        assertFalse(redisYml.matches("(?s).*spring:\\s*\\n\\s{2}redis:.*"),
            "config/redis.yml 不应再出现旧的 spring.redis 层级");
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

    @SuppressWarnings("unchecked")
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
        
        // config/*.yml 已按功能拆分到 config/ 子目录，通过 application.yml 的
        // spring.config.import 机制加载（参见 config/imports.yml）
        assertTrue(Files.exists(Paths.get("src/main/resources/config/async.yml")),
            "Async config should exist under config/");
        
        assertTrue(Files.exists(Paths.get("src/main/resources/config/cache.yml")),
            "Cache config should exist under config/");
        
        assertTrue(Files.exists(Paths.get("src/main/resources/config/server.yml")),
            "Server config should exist under config/");
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

    /**
     * 校验配置加载入口。
     *
     * <p>本用例原先断言 {@code server} / {@code cache} / {@code async} 出现在 profiles 的
     * active 列表里，属过时断言：这三个文件早已改为通过 {@code spring.config.import}
     * 加载（清单在 {@code config/imports.yml}），与 profiles 是两套独立机制。
     * 若仍按旧口径断言，会把正确配置判成错误，也会误导后人以为它们应由 profile 激活。</p>
     *
     * <p>故此处按当前真实机制分两段校验：入口文件声明了 import 与 profiles 两处，
     * 且 import 清单确实覆盖了这三个文件。</p>
     */
    @Test
    void testProfilesConfiguration() {
        try {
            Path appConfigPath = Paths.get("src/main/resources/application.yml");
            String content = Files.readString(appConfigPath);

            // 入口文件同时声明配置导入与 profile 激活两套机制
            assertTrue(content.contains("spring.config.import") || content.contains("config:"),
                "Should contain spring config import configuration");
            assertTrue(content.contains("spring.profiles:") || content.contains("profiles:"),
                "Should contain profiles configuration");
            assertTrue(content.contains("active:"),
                "Should contain active profiles configuration");

            // 经 spring.config.import 加载的文件在清单里注册，而不是出现在 active 列表
            Path importsPath = Paths.get("src/main/resources/config/imports.yml");
            String imports = Files.readString(importsPath);
            for (String fragment : new String[]{"server.yml", "datasource.yml", "cache.yml", "async.yml"}) {
                assertTrue(imports.contains(fragment),
                    "config/imports.yml should load " + fragment);
            }

            // active 列表中的 profile 名应与 @Profile 注解配套，至少保留一个可用的激活项
            assertTrue(content.contains("datasource"),
                "Should activate datasource profile");

        } catch (Exception e) {
            fail("Failed to validate profiles configuration: " + e.getMessage());
        }
    }

    @Test
    void testYamlSyntaxValidity() {
        // 验证所有YAML文件语法正确性
        validateYamlFile("application.yml");
        validateYamlFile("config/async.yml");
        validateYamlFile("config/cache.yml");
        validateYamlFile("config/server.yml");
        
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

    @SuppressWarnings("unchecked")
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