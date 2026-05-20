package io.yunxi.platform.shared.entity;

import io.yunxi.platform.infra.file.FileType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户文件实体
 *
 * <p>
 * 用于存储用户上传的文件元数据信息，包括文件信息、提取状态、向量化状态等。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFileEntity {

    /**
     * 文件ID（唯一标识）
     */
    private String id;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件类型
     */
    private FileType fileType;

    /**
     * 文件存储路径
     */
    private String filePath;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * MIME类型
     */
    private String mimeType;

    /**
     * 内容是否已提取
     */
    @Builder.Default
    private Boolean contentExtracted = false;

    /**
     * 是否已向量化
     */
    @Builder.Default
    private Boolean vectorized = false;

    /**
     * 提取的文本内容
     */
    private String content;

    /**
     * 元数据（JSON格式）
     */
    private String metadata;

    /**
     * 图像处理模式: ocr(文字识别)|feature(特征提取)
     * 仅对图片类型有效
     */
    private String processingMode;

    /**
     * 图像类型: face(人脸)|uniform(工装)|document(文档)|general(通用)
     * 仅当processingMode=feature时有效
     */
    private String imageType;

    /**
     * 特征向量维度
     * 仅当processingMode=feature时有效
     */
    private Integer featureDimension;

    /**
     * 特征提取模型名称
     */
    private String featureModel;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    // Lombok可能生成失败，手动添加必要的getter方法
    public String getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFilePath() {
        return filePath;
    }
}
