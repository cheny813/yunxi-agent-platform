package io.yunxi.platform.framework.skill;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * SkillBox 配置属性
 *
 * <p>在 application.yml 中配置：</p>
 * <pre>
 * agentscope:
 *   skill-box:
 *     enabled: true
 *     classpath-enabled: true
 *     filesystem-path: ./skills
 *     git-repositories:
 *       - url: https://github.com/agentscope/skills.git
 *         branch: main
 * </pre>
 *
 * @author yunxi-agent-platform
 */
@Data
@ConfigurationProperties(prefix = "agentscope.skill-box")
public class SkillBoxProperties {

    /**
     * 是否启用 SkillBox
     */
    private boolean enabled = true;

    /**
     * 是否从 classpath 加载预置 Skill（src/main/resources/skills/）
     */
    private boolean classpathEnabled = true;

    /**
     * classpath 下的 Skill 资源路径
     */
    private String classpathPath = "skills";

    /**
     * 本地文件系统 Skill 目录路径（相对于应用工作目录或绝对路径）
     */
    private String filesystemPath;

    /**
     * Git 远程 Skill 仓库列表
     */
    private List<GitRepoConfig> gitRepositories = new ArrayList<>();

    /**
     * 是否启用代码执行能力（Shell/Read/Write）
     */
    private boolean codeExecutionEnabled = false;

    /**
     * 代码执行工作目录（null 则使用临时目录）
     */
    private String codeExecutionWorkDir;

    /**
     * Git 仓库配置
     */
    @Data
    public static class GitRepoConfig {
        /**
         * Git 仓库 URL（HTTPS 或 SSH）
         */
        private String url;

        /**
         * 分支名（null 使用默认分支）
         */
        private String branch;

        /**
         * 自定义 source 标识（null 自动生成）
         */
        private String source;

        /**
         * 本地持久化路径（null 则使用临时目录，JVM 退出后自动清理）
         * <p>建议设为相对于项目的路径，如 ./skills/git/community-skills，
         * Git clone 的内容会与手写 Skill 共存于同一 skills 树下</p>
         */
        private String localPath;

        /**
         * 是否自动同步（默认 true，每次读取前轻量检查远端变化）
         */
        private boolean autoSync = true;
    }
}
