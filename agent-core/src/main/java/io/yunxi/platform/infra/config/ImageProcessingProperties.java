package io.yunxi.platform.infra.config;

import io.yunxi.platform.shared.constants.ConfigDefaults;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 图像处理配置属性
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "file-upload.image-processing")
public class ImageProcessingProperties {

    /**
     * 处理模式: ocr|feature|auto
     */
    private String mode = "auto";

    /**
     * 自动模式下的判断规则
     */
    private AutoRules autoRules = new AutoRules();

    /**
     * 自动模式判断规则配置类
     */
    @Data
    public static class AutoRules {
        /**
         * 特征提取关键词列表
         * 文件名包含这些关键词时使用特征提取模式
         */
        private List<String> featureKeywords = Arrays.asList(ConfigDefaults.DEFAULT_FEATURE_KEYWORDS);

        /**
         * 默认处理模式
         */
        private String defaultMode = "ocr";
    }

    /**
     * 判断文件应该使用的处理模式
     *
     * @param fileName 文件名
     * @return 处理模式 (ocr/feature)
     */
    public String determineProcessingMode(String fileName) {
        if ("auto".equalsIgnoreCase(mode)) {
            return autoModeDecision(fileName);
        }
        return mode.toLowerCase();
    }

    /**
     * 自动模式决策逻辑
     */
    private String autoModeDecision(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return autoRules.getDefaultMode();
        }

        String lowerFileName = fileName.toLowerCase();
        for (String keyword : autoRules.getFeatureKeywords()) {
            if (lowerFileName.contains(keyword.toLowerCase())) {
                return "feature";
            }
        }

        return autoRules.getDefaultMode();
    }
}
