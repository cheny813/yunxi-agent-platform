package io.yunxi.platform.infra.file.dto;

import io.yunxi.platform.infra.file.FileType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件检索请求DTO
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileSearchRequest {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 查询文本
     */
    private String query;

    /**
     * 文件类型（可选）
     */
    private FileType fileType;

    /**
     * 返回数量（默认10）
     */
    @Builder.Default
    private Integer topK = 10;

    /**
     * 相似度阈值（默认0.7）
     */
    @Builder.Default
    private Double threshold = 0.7;

    /**
     * 是否包含内容摘要
     */
    @Builder.Default
    private Boolean includeContent = true;
}
