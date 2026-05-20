package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * FixResult 修复结果
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class FixResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 修复的文件列表
     */
    private List<String> fixedFiles;

    /**
     * 修改的代码行数
     */
    private int linesChanged;

    /**
     * 生成的补丁
     */
    private String patch;

    /**
     * 输出数据
     */
    private Map<String, Object> output;

    /**
     * 创建成功结果
     *
     * @param fixedFiles 修复的文件列表
     * @return 成功的修复结果实例
     */
    public static FixResult success(List<String> fixedFiles) {
        return FixResult.builder()
                .success(true)
                .fixedFiles(fixedFiles)
                .linesChanged(0)
                .build();
    }

    /**
     * 创建失败结果
     *
     * @param message 失败消息
     * @return 失败的修复结果实例
     */
    public static FixResult failure(String message) {
        return FixResult.builder()
                .success(false)
                .fixedFiles(List.of())
                .build();
    }
}