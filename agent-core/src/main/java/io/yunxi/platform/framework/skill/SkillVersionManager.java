package io.yunxi.platform.framework.skill;

import io.yunxi.platform.framework.intelligent.config.IntelligentProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 版本管理器
 * <p>
 * 支持 Skill 的版本管理、A/B 测试和回滚。
 * 提供基于流量比例的 A/B 测试分流策略。
 * </p>
 *
 * <h3>功能</h3>
 * <ul>
 * <li>版本记录：保存每个版本的完整内容</li>
 * <li>A/B 测试：按流量比例分流到不同版本</li>
 * <li>版本回滚：恢复到历史版本</li>
 * <li>集群支持：内存存储，生产环境可切换为 Redis/DB</li>
 * </ul>
 *
 * <h3>A/B 测试策略</h3>
 * <p>
 * 通过 {@link #resolveVersion(String, String)} 方法实现流量分流：
 * 基于 userId 的哈希值 + trafficRatio 决定使用哪个版本。
 * 保证同一用户始终路由到同一版本（一致性哈希）。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 * @since 2026-04-14
 */
@Slf4j
@Component
public class SkillVersionManager {
    
    private static final Logger log = LoggerFactory.getLogger(SkillVersionManager.class);

    // 内存版本存储（生产环境应替换为 Redis 或数据库）
    private final Map<String, List<SkillVersion>> versionStore = new ConcurrentHashMap<>();

    // 当前活跃版本映射
    private final Map<String, String> activeVersion = new ConcurrentHashMap<>();

    // A/B 测试配置
    private final Map<String, ABTestConfig> abTestConfig = new ConcurrentHashMap<>();

    /**
     * 创建新版本
     *
     * @param skillName   技能名称
     * @param content     技能内容
     * @param description 版本描述
     * @return 版本 ID
     */
    public String createVersion(String skillName, String content, String description) {
        List<SkillVersion> versions = versionStore.computeIfAbsent(skillName, k -> new ArrayList<>());

        int versionNumber = versions.size() + 1;
        String versionId = skillName + "-v" + versionNumber;

        SkillVersion version = new SkillVersion();
        version.setVersionId(versionId);
        version.setSkillName(skillName);
        version.setVersionNumber(versionNumber);
        version.setContent(content);
        version.setDescription(description);
        version.setCreatedAt(LocalDateTime.now());
        version.setStatus(VersionStatus.DRAFT);

        versions.add(version);

        log.info("创建 Skill 版本: {}", versionId);
        return versionId;
    }

    /**
     * 发布版本
     * <p>
     * 将指定版本设为活跃版本，同时将其他活跃版本归档。
     * 如果存在 A/B 测试，会自动停止该技能的 A/B 测试。
     * </p>
     *
     * @param versionId 版本 ID
     * @return 是否成功
     */
    public boolean publishVersion(String versionId) {
        String skillName = extractSkillName(versionId);
        List<SkillVersion> versions = versionStore.get(skillName);
        if (versions == null) {
            return false;
        }

        // 查找版本
        SkillVersion version = versions.stream()
                .filter(v -> v.getVersionId().equals(versionId))
                .findFirst()
                .orElse(null);

        if (version == null) {
            return false;
        }

        // 设置为活跃版本
        version.setStatus(VersionStatus.ACTIVE);
        activeVersion.put(skillName, versionId);

        // 下线其他活跃版本
        versions.stream()
                .filter(v -> !v.getVersionId().equals(versionId))
                .filter(v -> v.getStatus() == VersionStatus.ACTIVE)
                .forEach(v -> v.setStatus(VersionStatus.ARCHIVED));

        // 发布正式版本时停止 A/B 测试
        abTestConfig.remove(skillName);

        log.info("发布 Skill 版本: {}", versionId);
        return true;
    }

    /**
     * 启动 A/B 测试
     * <p>
     * 将指定版本设为测试版本，与当前活跃版本进行对比测试。
     * 通过 {@link #resolveVersion(String, String)} 按流量比例分流。
     * </p>
     *
     * @param skillName     技能名称
     * @param testVersionId 测试版本 ID
     * @param trafficRatio  测试版本流量比例 (0.0-1.0)，例如 0.2 表示 20% 流量走测试版本
     * @return 是否成功
     */
    public boolean startABTest(String skillName, String testVersionId, double trafficRatio) {
        List<SkillVersion> versions = versionStore.get(skillName);
        if (versions == null) {
            log.warn("启动 A/B 测试失败: 技能不存在, skillName={}", skillName);
            return false;
        }

        // 验证测试版本存在
        SkillVersion testVersion = versions.stream()
                .filter(v -> v.getVersionId().equals(testVersionId))
                .findFirst()
                .orElse(null);
        if (testVersion == null) {
            log.warn("启动 A/B 测试失败: 版本不存在, versionId={}", testVersionId);
            return false;
        }

        // 验证存在活跃版本（作为对照版本）
        String activeVersionId = activeVersion.get(skillName);
        if (activeVersionId == null) {
            log.warn("启动 A/B 测试失败: 无活跃版本作为对照, skillName={}", skillName);
            return false;
        }

        // 校验流量比例
        if (trafficRatio <= 0.0 || trafficRatio >= 1.0) {
            log.warn("启动 A/B 测试失败: 流量比例须在 (0, 1) 范围内, trafficRatio={}", trafficRatio);
            return false;
        }

        // 设置测试版本状态
        testVersion.setStatus(VersionStatus.TESTING);

        // 保存 A/B 测试配置
        ABTestConfig config = new ABTestConfig();
        config.setControlVersionId(activeVersionId);
        config.setTestVersionId(testVersionId);
        config.setTrafficRatio(trafficRatio);
        config.setStartedAt(LocalDateTime.now());
        abTestConfig.put(skillName, config);

        log.info("启动 A/B 测试: skill={}, control={}, test={}, traffic={}",
                skillName, activeVersionId, testVersionId, trafficRatio);
        return true;
    }

    /**
     * 停止 A/B 测试
     *
     * @param skillName 技能名称
     * @return 是否成功
     */
    public boolean stopABTest(String skillName) {
        ABTestConfig config = abTestConfig.remove(skillName);
        if (config == null) {
            return false;
        }

        // 将测试版本状态从 TESTING 改回 DRAFT
        List<SkillVersion> versions = versionStore.get(skillName);
        if (versions != null) {
            versions.stream()
                    .filter(v -> v.getVersionId().equals(config.getTestVersionId()))
                    .filter(v -> v.getStatus() == VersionStatus.TESTING)
                    .forEach(v -> v.setStatus(VersionStatus.DRAFT));
        }

        log.info("停止 A/B 测试: skill={}", skillName);
        return true;
    }

    /**
     * 解析应该使用哪个版本
     * <p>
     * 如果存在 A/B 测试，基于 userId 的哈希值决定分流；
     * 否则返回活跃版本的内容。
     * </p>
     *
     * @param skillName 技能名称
     * @param userId    用户 ID（用于 A/B 测试分流，保证同一用户始终路由到同一版本）
     * @return 版本内容
     */
    public Optional<String> resolveVersion(String skillName, String userId) {
        ABTestConfig config = abTestConfig.get(skillName);

        if (config != null) {
            // A/B 测试分流：基于 userId 哈希值决定使用哪个版本
            double hash = Math.abs(userId.hashCode()) / (double) Integer.MAX_VALUE;
            String targetVersionId = hash < config.getTrafficRatio()
                    ? config.getTestVersionId()
                    : config.getControlVersionId();

            return findVersionContent(skillName, targetVersionId);
        }

        // 无 A/B 测试，返回活跃版本
        return getActiveVersion(skillName);
    }

    /**
     * 获取当前活跃版本内容
     *
     * @param skillName 技能名称
     * @return 当前版本内容
     */
    public Optional<String> getActiveVersion(String skillName) {
        String versionId = activeVersion.get(skillName);
        if (versionId == null) {
            return Optional.empty();
        }

        return findVersionContent(skillName, versionId);
    }

    /**
     * 回滚到指定版本
     *
     * @param versionId 目标版本 ID
     * @return 是否成功
     */
    public boolean rollbackTo(String versionId) {
        String skillName = extractSkillName(versionId);
        List<SkillVersion> versions = versionStore.get(skillName);
        if (versions == null) {
            return false;
        }

        // 查找版本
        Optional<SkillVersion> targetOpt = versions.stream()
                .filter(v -> v.getVersionId().equals(versionId))
                .findFirst();

        if (targetOpt.isEmpty()) {
            return false;
        }

        // 发布该版本（停止 A/B 测试，激活该版本）
        return publishVersion(versionId);
    }

    /**
     * 获取版本历史
     *
     * @param skillName 技能名称
     * @return 版本列表（按创建时间倒序）
     */
    public List<SkillVersion> getVersionHistory(String skillName) {
        List<SkillVersion> versions = versionStore.get(skillName);
        if (versions == null) {
            return List.of();
        }

        return versions.stream()
                .sorted((v1, v2) -> v2.getCreatedAt().compareTo(v1.getCreatedAt()))
                .toList();
    }

    /**
     * 获取 A/B 测试配置
     *
     * @param skillName 技能名称
     * @return A/B 测试配置，不存在则返回 empty
     */
    public Optional<ABTestConfig> getABTestConfig(String skillName) {
        return Optional.ofNullable(abTestConfig.get(skillName));
    }

    // ==================== 内部方法 ====================

    private Optional<String> findVersionContent(String skillName, String versionId) {
        List<SkillVersion> versions = versionStore.get(skillName);
        if (versions == null) {
            return Optional.empty();
        }

        return versions.stream()
                .filter(v -> v.getVersionId() != null && v.getVersionId().equals(versionId))
                .map(v -> v.getContent())
                .findFirst();
    }

    private String extractSkillName(String versionId) {
        // 从 versionId 提取 skillName（去掉 -v数字 后缀）
        int idx = versionId.lastIndexOf("-v");
        return idx > 0 ? versionId.substring(0, idx) : versionId;
    }

    // ==================== 内部数据结构 ====================

    /**
     * Skill 版本
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillVersion {
        /** 版本ID */
        private String versionId;
        /** 技能名称 */
        private String skillName;
        /** 版本号 */
        private int versionNumber;
        /** 技能内容 */
        private String content;
        /** 版本描述 */
        private String description;
        /** 创建时间 */
        private LocalDateTime createdAt;
        /** 版本状态 */
        private VersionStatus status;
        
        // 手动添加getter/setter方法
        public String getVersionId() { return versionId; }
        public void setVersionId(String versionId) { this.versionId = versionId; }
        public String getSkillName() { return skillName; }
        public void setSkillName(String skillName) { this.skillName = skillName; }
        public int getVersionNumber() { return versionNumber; }
        public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public VersionStatus getStatus() { return status; }
        public void setStatus(VersionStatus status) { this.status = status; }
    }

    /**
     * A/B 测试配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ABTestConfig {
        /** 对照版本 ID（当前活跃版本） */
        private String controlVersionId;
        /** 测试版本 ID */
        private String testVersionId;
        /** 测试版本流量比例 (0.0-1.0) */
        private double trafficRatio;
        /** 测试启动时间 */
        private LocalDateTime startedAt;
        
        // 手动添加getter/setter方法
        public String getControlVersionId() { return controlVersionId; }
        public void setControlVersionId(String controlVersionId) { this.controlVersionId = controlVersionId; }
        public String getTestVersionId() { return testVersionId; }
        public void setTestVersionId(String testVersionId) { this.testVersionId = testVersionId; }
        public double getTrafficRatio() { return trafficRatio; }
        public void setTrafficRatio(double trafficRatio) { this.trafficRatio = trafficRatio; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    }

    /**
     * 版本状态
     */
    public enum VersionStatus {
        DRAFT, // 草稿
        TESTING, // 测试中
        ACTIVE, // 活跃
        ARCHIVED // 归档
    }
}
