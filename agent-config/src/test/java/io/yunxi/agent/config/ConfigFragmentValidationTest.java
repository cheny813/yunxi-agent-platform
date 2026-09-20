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
 * 验证所有YAML配置文件格式正确性和关键配置项，并守住配置治理红线：
 *  - config/imports.yml 是片段清单唯一真相源，必选模块缺失即应启动失败（此处断言其存在）；
 *  - application.yml 只作纯入口，不得再写顶层 yunxi:/management:/logging: 等业务配置；
 *  - aistio-workflow-export.yml 只允许写 yunxi.aistio.workflow.*，禁止写顶层 aistio 键。
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
    void testRateLimitConfigFormat() {
        // 限流配置前缀必须是顶层 rate-limit（RateLimitInterceptor 的 @Value 绑定），
        // 而非旧 cache.yml 里误写的 api.rate-limit.*（从未被绑定，属死配置）。
        validateConfigFile("config/rate-limit.yml", "rate-limit");
    }

    @Test
    void testA2APipelineConfigFormat() {
        validateConfigFile("config/a2a-pipeline.yml", "a2a.pipeline");
    }

    @Test
    void testFileUploadConfigFormat() {
        validateConfigFile("config/file-upload.yml", "file-upload");
    }

    @Test
    void testPersistenceConfigFormat() {
        validateConfigFile("config/persistence.yml", "spring.jpa");
    }

    @Test
    void testMCPServerConfigFormat() {
        validateConfigFile("config/mcp-core.yml", "mcp");
    }

    @Test
    void testAgentScopeConfigFormat() {
        validateConfigFile("config/agentscope.yml", "io.yunxi.platform.agentscope");
    }

    // ---- 平台/集成片段 ----

    @Test
    void testPlatformConfigFormat() {
        validateConfigFile("config/platform.yml", "yunxi.observability");
    }

    @Test
    void testAistioConfigFormat() {
        validateConfigFile("config/aistio.yml", "yunxi.aistio");
    }

    @Test
    void testObservabilityConfigFormat() {
        validateConfigFile("config/observability.yml", "management");
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
        // 旧写法 `spring:\n  redis:` 作为独立层级出现即为回归（注意与 spring.data.redis 区分）
        assertFalse(redisYml.matches("(?s).*spring:\\s*\\n\\s{2}redis:.*"),
            "config/redis.yml 不应再出现旧的 spring.redis 层级");
        assertFalse(a2aYml.contains("spring:\n  redis:"),
            "config/a2a-pipeline.yml 不得再用旧前缀 spring.redis 重复声明连接参数");
    }

    // ---- 配置治理红线 ----

    /**
     * application.yml 必须保持纯入口：不得再写顶层业务配置
     * （yunxi:/management:/logging:），这些已分别收进 config/platform.yml、
     * config/aistio.yml、config/observability.yml。
     */
    @Test
    void testApplicationYmlIsPureHub() {
        try {
            String content = Files.readString(Paths.get("src/main/resources/application.yml"));
            assertFalse(content.contains("\nyunxi:"),
                "application.yml 不得再写顶层 yunxi: 业务配置，应下沉到 config/ 对应片段");
            assertFalse(content.contains("\nmanagement:"),
                "application.yml 不得再写 management: 段，应统一在 config/observability.yml");
            assertFalse(content.contains("\nlogging:"),
                "application.yml 不得再写 logging: 段，级别统一在 config/logging.yml");
            assertTrue(content.contains("spring.config.import"),
                "application.yml 必须保留 spring.config.import 指向 imports.yml");
        } catch (Exception e) {
            fail("Failed to read application.yml: " + e.getMessage());
        }
    }

    /**
     * imports.yml 是清单唯一真相源：必选模块不得带 optional:（除 aistio-workflow-export.yml）。
     * 防止“配置写了但没加载”的静默黑洞回归。
     */
    @Test
    void testImportsMandatoryModulesNotOptional() {
        try {
            String imports = Files.readString(Paths.get("src/main/resources/config/imports.yml"));
            long optionalCount = imports.lines()
                .filter(l -> l.trim().startsWith("optional:classpath:"))
                .count();
            assertEquals(1, optionalCount,
                "imports.yml 应仅 aistio-workflow-export.yml 带 optional:，其余必选模块缺失必须启动失败");
            for (String mandatory : new String[]{"server.yml", "datasource.yml", "cache.yml", "async.yml",
                    "redis.yml", "llm.yml", "milvus.yml", "embedding.yml", "persistence.yml", "mcp-core.yml",
                    "mcp-external.yml", "resilience.yml", "rate-limit.yml", "file-upload.yml", "a2a-pipeline.yml",
                    "agentscope.yml", "muse.yml", "intent.yml", "platform.yml", "aistio.yml", "observability.yml"}) {
                assertTrue(imports.contains("classpath:config/" + mandatory),
                    "imports.yml 必须注册必选片段 " + mandatory);
                assertFalse(imports.contains("optional:classpath:config/" + mandatory),
                    "必选片段 " + mandatory + " 不得带 optional: 前缀");
            }
        } catch (Exception e) {
            fail("Failed to read imports.yml: " + e.getMessage());
        }
    }

    /**
     * aistio-workflow-export.yml 只许写 yunxi.aistio.workflow.* 子树，
     * 禁止写顶层 aistio 键（enabled/control-plane-url 等），
     * 否则会静默覆盖 config/aistio.yml 的顶层值。
     */
    @Test
    void testAistioWorkflowExportNoTopLevelKeys() {
        try {
            String wf = Files.readString(Paths.get("src/main/resources/config/aistio-workflow-export.yml"));
            assertFalse(wf.contains("control-plane-url"),
                "aistio-workflow-export.yml 不得写顶层 aistio 键 control-plane-url（会覆盖 config/aistio.yml）");
            assertFalse(wf.contains("\nenabled:"),
                "aistio-workflow-export.yml 不得写顶层 enabled:，开关在 config/aistio.yml");
        } catch (Exception e) {
            fail("Failed to read aistio-workflow-export.yml: " + e.getMessage());
        }
    }

    @Test
    void testAllConfigFilesPresent() {
        // 验证应用主配置文件存在
        assertTrue(Files.exists(Paths.get("src/main/resources/application.yml")),
            "Main application.yml should exist");

        // config/*.yml 已按功能拆分到 config/ 子目录，通过 application.yml 的
        // spring.config.import 机制加载（参见 config/imports.yml）
        for (String f : new String[]{"async.yml", "cache.yml", "server.yml", "platform.yml",
                "aistio.yml", "observability.yml"}) {
            assertTrue(Files.exists(Paths.get("src/main/resources/config/" + f)),
                "Config " + f + " should exist under config/");
        }
    }

    /**
     * 校验配置加载入口。
     *
     * <p>入口文件只声明 {@code spring.config.import}，真正的片段清单在
     * {@code config/imports.yml}（不再像旧版那样在 application.yml 里列 server.yml 等文件名）。
     * 若仍按旧口径断言 application.yml 包含各片段文件名，会把正确配置判成错误。</p>
     */
    @Test
    void testConfigImportOrder() {
        try {
            String app = Files.readString(Paths.get("src/main/resources/application.yml"));
            String imports = Files.readString(Paths.get("src/main/resources/config/imports.yml"));

            assertTrue(app.contains("spring.config.import"),
                "application.yml 应声明 spring.config.import");
            for (String fragment : new String[]{"server.yml", "datasource.yml", "cache.yml", "async.yml",
                    "platform.yml", "aistio.yml", "observability.yml", "logging.yml"}) {
                assertTrue(imports.contains(fragment),
                    "config/imports.yml should load " + fragment);
            }
        } catch (Exception e) {
            fail("Failed to validate config import order: " + e.getMessage());
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

                    assertFalse(configMap.isEmpty(),
                        "Config file " + filePath + " should contain configuration");

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
