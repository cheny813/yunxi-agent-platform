package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * CodeFixContext 代码修复上下文
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class CodeFixContext {

    /**
     * 仓库URL
     */
    private String repositoryUrl;

    /**
     * 分支名称
     */
    private String branch;

    /**
     * 相关的文件列表
     */
    private List<String> relatedFiles;

    /**
     * 堆栈跟踪信息
     */
    private String stackTrace;

    /**
     * 错误日志
     */
    private String errorLog;

    /**
     * 附加元数据
     */
    private Map<String, Object> metadata;
}