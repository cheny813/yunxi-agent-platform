package io.yunxi.platform.infra.file.extractor;

import io.yunxi.platform.shared.entity.UserFileEntity;
import io.yunxi.platform.infra.file.dto.ImageFeatureResult;

/**
 * 图像特征提取器接口
 *
 * <p>
 * 用于从图像中提取视觉特征向量，支持人脸识别、工装识别等场景
 * 与OCR不同，此接口不提取文字内容，而是提取图像的视觉特征
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface ImageFeatureExtractor {

    /**
     * 提取图像特征
     *
     * @param file 文件信息
     * @return 特征提取结果，包含特征向量
     * @throws Exception 提取失败时抛出异常
     */
    ImageFeatureResult extract(UserFileEntity file) throws Exception;

    /**
     * 检查是否支持该文件类型
     *
     * @param imageType 图像类型（face/uniform/document等）
     * @return 是否支持
     */
    boolean supports(String imageType);

    /**
     * 获取提取器名称
     */
    String getName();

    /**
     * 获取特征向量维度
     */
    int getFeatureDimension();
}
