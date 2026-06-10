package io.yunxi.platform.tool;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.tool.coding.ShellCommandTool;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;

/**
 * Shell 命令工具工厂 — 封装框架 {@link ShellCommandTool} 的创建
 * <p>
 * 依赖框架内置的 Shell 安全机制：白名单 allowlist + 人工审批回调 + 平台验证
 * 替代原本的 {@code CommandSafetyClassifier} 命令分类系统
 * </p>
 *
 * <p>
 * 配置示例（application.yml）：
 * 
 * <pre>{@code
 * agentscope.core.shell:
 *   allowed-commands: [ls, cat, grep, python, node]
 *   approval-enabled: true
 *   base-dir: /data/workspace
 * }</pre>
 * </p>
 *
 * <p>
 * 安全策略：
 * <ol>
 * <li>白名单内的命令 — 自动执行</li>
 * <li>白名单外的命令 — 检查审批回调则等待人工审批，否则直接拒绝</li>
 * <li>命令分隔符检测（&amp;、|等） — 拒绝</li>
 * <li>路径穿越检测（../） — 拒绝</li>
 * </ol>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class ShellToolFactory {

    private static final Logger log = LoggerFactory.getLogger(ShellToolFactory.class);

    private final AgentscopeCoreProperties properties;

    public ShellToolFactory(AgentscopeCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 创建 Shell 命令工具，使用全局默认安全配置
     *
     * @return 配置完成的 ShellCommandTool
     */
    public ShellCommandTool create() {
        var shellConfig = properties.getShell();
        return create(shellConfig.getAllowedCommands(), shellConfig.isApprovalEnabled());
    }

    /**
     * 创建 Shell 命令工具，注入框架的白名单和审批机制
     *
     * @param allowedCommands 允许自动执行的命令集合（null 或空 = 全部需审批）
     * @param approvalEnabled 是否启用人工审批回调
     * @return 配置完成的 ShellCommandTool
     */
    public ShellCommandTool create(Set<String> allowedCommands, boolean approvalEnabled) {
        var shellConfig = properties.getShell();

        // 使用框架 ShellCommandTool 的全参数构造器，框架自动选择 Unix/Windows 验证器
        // 第一个参数是 baseDir 字符串（null = 不限制工作目录）
        return new ShellCommandTool(
                shellConfig.getBaseDir(),
                allowedCommands != null ? allowedCommands : Set.of(),
                approvalEnabled ? this::requestApproval : null, // 审批回调，null=直接拒绝
                null // null = 使用框架默认验证器（自动根据 OS 选择 UnixCommandValidator / WindowsCommandValidator）
        );
    }

    /**
     * 人工审批回调 — 记录请求并当前默认拒绝
     * <p>
     * 实际生产环境应替换为实际的审批系统集成（如消息队列推送到前端审批页面）
     * </p>
     *
     * @param command 待审批的完整命令字符串
     * @return true 允许执行，false 拒绝执行
     */
    private boolean requestApproval(String command) {
        log.warn("Shell 命令需人工审批（当前默认拒绝，请集成实际审批系统）: {}", command);
        // TODO: 替换为实际的审批系统调用
        return false;
    }
}
