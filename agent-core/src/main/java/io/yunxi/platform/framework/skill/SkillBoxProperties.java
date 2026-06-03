package io.yunxi.platform.framework.skill;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SkillBox 配置属性
 *
 * <p>
 * 在 application.yml 中配置：
 * </p>
 *
 * <pre>
 * agentscope:
 *   skill-box:
 *     enabled: true
 *     classpath-enabled: true
 *     filesystem-path: ./skills
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
     * 是否启用代码执行能力（Shell/Read/Write）
     */
    private boolean codeExecutionEnabled = false;

    /**
     * 代码执行工作目录（null 则使用临时目录）
     */
    private String codeExecutionWorkDir;
}
