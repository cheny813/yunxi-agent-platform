package io.yunxi.agent.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置文件加载和验证测试
 * 验证Spring配置文件格式正确性和关键配置项存在性
 *
 * 注意：本模块（agent-config）是纯配置模块，不包含 @SpringBootApplication。
 * Spring 容器启动测试位于 agent-core 模块的 io.yunxi.platform.config.ConfigValidationTest。
 */
class ConfigValidationTest {

    private final Yaml yaml = new Yaml();

    @Test
    void testAllConfigFilesLoadable() {
        // 验证所有关键配置文件可解析
        String[] configFiles = {
            "config/server.yml", "config/datasource.yml", "config/cache.yml",
            "config/async.yml", "config/redis.yml", "config/llm.yml",
            "config/milvus.yml", "config/embedding.yml", "config/persistence.yml",
            "config/mcp-core.yml", "config/mcp-business.yml", "config/mcp-external.yml",
            "config/skill.yml", "config/resilience.yml", "config/file-upload.yml",
            "config/a2a-pipeline.yml", "config/gateway.yml", "config/business.yml",
            "config/text2sql.yml", "config/rule-engine.yml"
        };

        for (String filePath : configFiles) {
            Path path = Paths.get("src/main/resources", filePath);
            if (Files.exists(path)) {
                try (InputStream input = Files.newInputStream(path)) {
                    Object data = yaml.load(input);
                    assertNotNull(data, filePath + " should parse correctly");
                    if (data instanceof Map) {
                        assertFalse(((Map<?, ?>) data).isEmpty(), filePath + " should not be empty");
                    }
                } catch (Exception e) {
                    fail("Failed to parse " + filePath + ": " + e.getMessage());
                }
            } else {
                System.out.println("Skipping non-existent config: " + filePath);
            }
        }
    }

    @Test
    void testApplicationYmlStructure() {
        try {
            Path appConfigPath = Paths.get("src/main/resources/application.yml");
            String content = Files.readString(appConfigPath);

            assertTrue(content.contains("spring:"), "Should contain spring configuration");
            assertTrue(content.contains("config:"), "Should contain config section");
            assertTrue(content.contains("import:"), "Should contain import directive");
            assertTrue(content.contains("management:"), "Should contain management configuration");
        } catch (Exception e) {
            fail("Failed to validate application.yml structure: " + e.getMessage());
        }
    }

    @Test
    void testConfigImportOrder() {
        try {
            Path importsPath = Paths.get("src/main/resources/config/imports.yml");
            if (Files.exists(importsPath)) {
                String content = Files.readString(importsPath);
                assertTrue(content.contains("import:"), "imports.yml should contain import directive");
                assertTrue(content.contains("server.yml"), "Should import server.yml");
                assertTrue(content.contains("datasource.yml"), "Should import datasource.yml");
                assertTrue(content.contains("cache.yml"), "Should import cache.yml");
                assertTrue(content.contains("async.yml"), "Should import async.yml");
            }
        } catch (Exception e) {
            fail("Failed to validate config import order: " + e.getMessage());
        }
    }
}