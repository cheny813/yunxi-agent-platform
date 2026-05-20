package io.yunxi.platform.infra.file.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import io.yunxi.platform.infra.file.FileType;

/**
 * 文件检索结果DTO
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileSearchResult {

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
     * 相似度（0-1）
     */
    private Double similarity;

    /**
     * 匹配的内容片段
     */
    private String matchedContent;

    /**
     * 完整内容
     */
    private String content;

    /**
     * 上传时间
     */
    private LocalDateTime uploadTime;

    /**
     * 高亮内容（用于前端展示）
     */
    private String highlightedContent;
}
