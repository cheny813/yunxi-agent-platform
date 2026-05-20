package io.yunxi.platform.infra.file.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 图像特征提取结果
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageFeatureResult {

    /**
     * 特征向量
     */
    private List<Float> featureVector;

    /**
     * 特征维度
     */
    private Integer dimension;

    /**
     * 使用的模型名称
     */
    private String modelName;

    /**
     * 图像类型（face/uniform/document等）
     */
    private String imageType;

    /**
     * 置信度（如果适用）
     */
    private Float confidence;

    /**
     * 额外元数据
     */
    private Map<String, Object> metadata;

    /**
     * 创建成功结果
     */
    public static ImageFeatureResult success(List<Float> featureVector, String modelName, String imageType) {
        return ImageFeatureResult.builder()
                .featureVector(featureVector)
                .dimension(featureVector != null ? featureVector.size() : 0)
                .modelName(modelName)
                .imageType(imageType)
                .build();
    }
}
