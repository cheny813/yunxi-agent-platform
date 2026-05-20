package io.yunxi.platform.infra.file.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件上传请求DTO
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadRequest {

    /**
     * 文件类型（可选，自动检测）
     */
    private String type;

    /**
     * 是否立即提取内容
     */
    @Builder.Default
    private Boolean extractContent = true;

    /**
     * 是否立即向量化
     */
    @Builder.Default
    private Boolean vectorize = true;

    /**
     * 图像处理模式（可选，仅对图片有效）
     *
     * <p>
     * 可选值：
     * - ocr: 文字识别模式，提取图片中的文字内容
     * - feature: 特征提取模式，提取图像视觉特征（人脸、工装等）
     * - auto: 自动模式（根据文件名自动判断，需要后端配置支持）
     *
     * <p>
     * 如果不传，则根据后端配置或文件名自动判断
     */
    private String processingMode;

    /**
     * 图像类型（可选，仅当processingMode=feature时有效）
     *
     * <p>
     * 可选值：
     * - face: 人脸图片（用于人脸识别）
     * - uniform: 工装图片（用于工装识别）
     * - document: 文档图片（用于文档识别）
     * - general: 通用图片（用于通用图像搜索）
     *
     * <p>
     * 如果不传，则根据文件名自动判断
     */
    private String imageType;
}
