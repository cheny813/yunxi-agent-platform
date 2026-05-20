package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * FixOutput 修复输出
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class FixOutput {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 修复的文件列表
     */
    private List<String> fixedFiles;

    /**
     * 修改统计
     */
    private Map<String, Integer> changeStats;

    /**
     * 生成代码行数
     */
    private int linesAdded;

    /**
     * 删除代码行数
     */
    private int linesRemoved;

    /**
     * 创建成功输出
     *
     * @param fixedFiles 修复的文件列表
     * @return 成功的修复输出实例
     */
    public static FixOutput success(List<String> fixedFiles) {
        return FixOutput.builder()
                .success(true)
                .fixedFiles(fixedFiles)
                .build();
    }

    /**
     * 创建失败输出
     *
     * @param error 错误信息
     * @return 失败的修复输出实例
     */
    public static FixOutput failure(String error) {
        return FixOutput.builder()
                .success(false)
                .fixedFiles(List.of())
                .build();
    }
}