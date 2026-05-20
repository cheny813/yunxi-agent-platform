package io.yunxi.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 配置读取器 - 负责从属性文件加载配置
 */
public class ConfigurationReader {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationReader.class);
    
    /**
     * 从指定文件路径加载属性配置
     * 
     * @param filePath 属性文件路径
     * @return 配置键值对映射
     * @throws IOException 当文件读取失败时抛出
     */
    public static Map<String, String> loadProperties(String filePath) throws IOException {
        Path path = Path.of(filePath);
        
        if (!Files.exists(path)) {
            throw new IOException("配置文件不存在: " + filePath);
        }
        
        Properties properties = new Properties();
        Map<String, String> configMap = new HashMap<>();
        
        try (var reader = Files.newBufferedReader(path)) {
            properties.load(reader);
            
            // 将Properties转换为Map
            for (String key : properties.stringPropertyNames()) {
                configMap.put(key, properties.getProperty(key));
            }
            
            logger.debug("成功加载配置文件: {}, 共加载 {} 个配置项", filePath, configMap.size());
            
        } catch (IOException e) {
            logger.error("读取配置文件失败: {}", filePath, e);
            throw e;
        }
        
        return configMap;
    }
    
    /**
     * 从指定文件路径加载属性配置，如果文件不存在则返回空映射
     * 
     * @param filePath 属性文件路径
     * @return 配置键值对映射，如果文件不存在则返回空映射
     */
    public static Map<String, String> loadPropertiesSafe(String filePath) {
        try {
            return loadProperties(filePath);
        } catch (IOException e) {
            logger.warn("配置文件不存在或读取失败: {}, 返回空配置", filePath);
            return new HashMap<>();
        }
    }
    
    /**
     * 检查配置文件是否存在
     * 
     * @param filePath 文件路径
     * @return 文件是否存在
     */
    public static boolean configFileExists(String filePath) {
        return Files.exists(Path.of(filePath));
    }
}