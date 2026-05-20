package io.yunxi.platform.infra.file.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import io.yunxi.platform.infra.file.FileType;

/**
 * 文件上传响应DTO
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadResponse {

    /**
     * 文件ID
     */
    private String id;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件类型
     */
    private FileType fileType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 内容是否已提取
     */
    private Boolean contentExtracted;

    /**
     * 是否已向量化
     */
    private Boolean vectorized;

    /**
     * 提取的文本内容（如果已提取）
     */
    private String content;

    /**
     * 上传时间
     */
    private LocalDateTime uploadTime;

    /**
     * 消息
     */
    private String message;

    /**
     * 是否成功
     */
    @Builder.Default
    private Boolean success = true;
}
