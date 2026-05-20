package io.yunxi.platform.shared.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * StageResult 阶段结果
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class StageResult {
    
    // 手动添加构造器以匹配Builder模式
    public StageResult(String stageName, StageStatus status, long startTimeMs, long endTimeMs, 
                      long durationMs, List<Finding> findings, Map<String, Object> output) {
        this.stageName = stageName;
        this.status = status;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.durationMs = durationMs;
        this.findings = findings;
        this.output = output;
    }
    
    // 无参构造器（保持原有Lombok行为）
    public StageResult() {
    }
    
    public static StageResultBuilder builder() {
        return new StageResultBuilder();
    }
    
    public static class StageResultBuilder {
        private String stageName;
        private StageStatus status;
        private long startTimeMs;
        private long endTimeMs;
        private long durationMs;
        private List<Finding> findings;
        private Map<String, Object> output;
        
        public StageResultBuilder stageName(String stageName) {
            this.stageName = stageName;
            return this;
        }
        
        public StageResultBuilder status(StageStatus status) {
            this.status = status;
            return this;
        }
        
        public StageResultBuilder startTimeMs(long startTimeMs) {
            this.startTimeMs = startTimeMs;
            return this;
        }
        
        public StageResultBuilder endTimeMs(long endTimeMs) {
            this.endTimeMs = endTimeMs;
            return this;
        }
        
        public StageResultBuilder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }
        
        public StageResultBuilder findings(List<Finding> findings) {
            this.findings = findings;
            return this;
        }
        
        public StageResultBuilder output(Map<String, Object> output) {
            this.output = output;
            return this;
        }
        
        public StageResult build() {
            return new StageResult(this.stageName, this.status, this.startTimeMs, this.endTimeMs, 
                                  this.durationMs, this.findings, this.output);
        }
    }

    /**
     * 阶段名称
     */
    private String stageName;

    /**
     * 状态
     */
    private StageStatus status;

    /**
     * 开始时间
     */
    private long startTimeMs;

    /**
     * 结束时间
     */
    private long endTimeMs;

    /**
     * 执行耗时(毫秒)
     */
    private long durationMs;

    /**
     * 发现的问题列表
     */
    private List<Finding> findings;

    /**
     * 输出数据
     */
    private Map<String, Object> output;

    /**
     * 阶段状态枚举
     */
    public enum StageStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    /**
     * Finding 发现的问题
     */
    @Data
    @Builder
    public static class Finding {
        // 手动添加构造器以匹配Builder模式
        public Finding(String title, String severity, String description, String filePath, int lineNumber) {
            this.title = title;
            this.severity = severity;
            this.description = description;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
        }
        
        // 无参构造器
        public Finding() {
        }
        
        private String title;
        private String severity;
        private String description;
        private String filePath;
        private int lineNumber;
        
        public static FindingBuilder builder() {
            return new FindingBuilder();
        }
        
        public static class FindingBuilder {
            private String title;
            private String severity;
            private String description;
            private String filePath;
            private int lineNumber;
            
            public FindingBuilder title(String title) {
                this.title = title;
                return this;
            }
            
            public FindingBuilder severity(String severity) {
                this.severity = severity;
                return this;
            }
            
            public FindingBuilder description(String description) {
                this.description = description;
                return this;
            }
            
            public FindingBuilder filePath(String filePath) {
                this.filePath = filePath;
                return this;
            }
            
            public FindingBuilder lineNumber(int lineNumber) {
                this.lineNumber = lineNumber;
                return this;
            }
            
            public Finding build() {
                return new Finding(this.title, this.severity, this.description, this.filePath, this.lineNumber);
            }
        }
    }
}
