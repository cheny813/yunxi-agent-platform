package io.yunxi.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigurationReader 配置读取器单元测试
 */
class ConfigurationReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void testLoadPropertiesFromValidFile() throws IOException {
        // 创建测试属性文件
        Path configFile = tempDir.resolve("test.properties");
        Files.writeString(configFile, """
            agent.name=test-agent
            agent.api.key=test-api-key
            agent.model.name=gpt-4
            agent.provider=openai
            """);

        Map<String, String> config = ConfigurationReader.loadProperties(configFile.toString());

        assertNotNull(config);
        assertEquals("test-agent", config.get("agent.name"));
        assertEquals("test-api-key", config.get("agent.api.key"));
        assertEquals("gpt-4", config.get("agent.model.name"));
        assertEquals("openai", config.get("agent.provider"));
    }

    @Test
    void testLoadPropertiesFromNonExistentFile() {
        assertThrows(IOException.class, () -> {
            ConfigurationReader.loadProperties("/non/existent/file.properties");
        });
    }

    @Test
    void testLoadPropertiesFromEmptyFile() throws IOException {
        Path configFile = tempDir.resolve("empty.properties");
        Files.writeString(configFile, "");

        Map<String, String> config = ConfigurationReader.loadProperties(configFile.toString());

        assertNotNull(config);
        assertTrue(config.isEmpty());
    }

    @Test
    void testLoadPropertiesWithComments() throws IOException {
        Path configFile = tempDir.resolve("comments.properties");
        Files.writeString(configFile, """
            # This is a comment
            agent.name=test-agent
            # Another comment
            agent.api.key=test-key
            # Final comment
            """);

        Map<String, String> config = ConfigurationReader.loadProperties(configFile.toString());

        assertNotNull(config);
        assertEquals(2, config.size());
        assertEquals("test-agent", config.get("agent.name"));
        assertEquals("test-key", config.get("agent.api.key"));
    }

    @Test
    void testLoadPropertiesWithBlankLines() throws IOException {
        Path configFile = tempDir.resolve("blank.properties");
        Files.writeString(configFile, """
            
            agent.name=test-agent
            
            agent.api.key=test-key
            
            """);

        Map<String, String> config = ConfigurationReader.loadProperties(configFile.toString());

        assertNotNull(config);
        assertEquals(2, config.size());
        assertEquals("test-agent", config.get("agent.name"));
        assertEquals("test-key", config.get("agent.api.key"));
    }

    @Test
    void testLoadPropertiesWithSpecialCharacters() throws IOException {
        Path configFile = tempDir.resolve("special.properties");
        Files.writeString(configFile, """
            agent.name=test-agent-with-dash
            agent.url=https://api.example.com/v1/endpoint
            agent.secret=key_with_underscore@123!
            """);
        
        Map<String, String> config = ConfigurationReader.loadProperties(configFile.toString());
        assertNotNull(config);
        assertEquals("test-agent-with-dash", config.get("agent.name"));
        assertEquals("https://api.example.com/v1/endpoint", config.get("agent.url"));
        assertEquals("key_with_underscore@123!", config.get("agent.secret"));
    }
}