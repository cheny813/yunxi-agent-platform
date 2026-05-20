package io.yunxi.platform.framework.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 命令安全分级器
 *
 * <p>对 AI 生成的命令进行安全分级，防止误操作和恶意命令。</p>
 *
 * <p>分级策略（按优先级从高到低）：</p>
 * <ol>
 *   <li>黑名单模式匹配 → BLOCKED</li>
 *   <li>白名单模式匹配 → SAFE</li>
 *   <li>危险模式匹配 → DANGEROUS</li>
 *   <li>警告模式匹配 → WARNING</li>
 *   <li>默认 → WARNING（未知命令默认需确认）</li>
 * </ol>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "node.security")
public class CommandSafetyClassifier {

    /** 阻止的命令模式（正则） */
    private List<String> blockedPatterns = new ArrayList<>(List.of(
            "rm\\s+-rf\\s+/",           // rm -rf /
            "dd\\s+if=.*of=/dev/",      // dd 写设备
            "mkfs\\.",                   // 格式化
            ":\\(\\)\\s*\\{.*\\}",       // fork bomb
            "shutdown",                  // 关机
            "reboot",                    // 重启
            "halt",                      // 停机
            "poweroff",                  // 断电
            "init\\s+[06]",             // 切换运行级别
            ">\\s*/dev/sd",             // 直接写磁盘设备
            "chmod\\s+777\\s+/",        // 全权限根目录
            "chown\\s+.*\\s+/"          // 修改根目录属主
    ));

    /** 危险的命令模式（正则） */
    private List<String> dangerousPatterns = new ArrayList<>(List.of(
            "rm\\s+",                    // rm 删除
            "drop\\s+",                  // SQL DROP
            "truncate\\s+",             // SQL TRUNCATE
            "delete\\s+from",           // SQL DELETE (无 WHERE)
            "kill\\s+-9",               // 强杀进程
            "killall",                   // 杀所有
            "pkill",                     // 按名杀进程
            "iptables",                  // 防火墙规则
            "ufw",                       // 防火墙
            "firewall-cmd",             // 防火墙
            "systemctl\\s+(stop|disable|mask)\\s+",  // 停止服务
            "docker\\s+(rm|rmi)\\s+",   // 删除容器/镜像
            "docker\\s+system\\s+prune", // 清理 Docker
            "git\\s+push\\s+--force",   // 强推
            "git\\s+reset\\s+--hard",   // 硬重置
            "format\\s+[A-Z]:",         // Windows 格式化
            "del\\s+/[sq]\\s+[A-Z]:",   // Windows 删除
            "net\\s+user.*delete",      // Windows 删用户
            "reg\\s+delete"             // Windows 删注册表
    ));

    /** 警告的命令模式（正则） */
    private List<String> warningPatterns = new ArrayList<>(List.of(
            "systemctl\\s+(restart|reload)\\s+",  // 重启服务
            "service\\s+\\w+\\s+restart",         // 重启服务
            "docker\\s+(restart|stop|start)\\s+", // Docker 操作
            "apt\\s+(install|remove)",            // 包管理
            "yum\\s+(install|remove)",            // 包管理
            "npm\\s+(install|uninstall)",         // npm 操作
            "pip\\s+(install|uninstall)",         // pip 操作
            "git\\s+(reset|checkout)\\s+",        // Git 回退
            "git\\s+clean",                       // Git 清理
            "mv\\s+.*\\s+/(dev|null|tmp)",        // 移动到特殊路径
            "cp\\s+-r\\s+/",                      // 递归复制根目录
            "curl.*\\|\\s*sh",                     // 管道执行远程脚本
            "wget.*\\|\\s*sh",                     // 管道执行远程脚本
            "chmod",                              // 修改权限
            "chown",                              // 修改属主
            "crontab"                             // 定时任务
    ));

    /** 安全的命令模式（正则） */
    private List<String> safePatterns = new ArrayList<>(List.of(
            "^ls\\b",                    // 列目录
            "^cat\\b",                   // 读文件
            "^head\\b",                  // 读头部
            "^tail\\b",                  // 读尾部
            "^grep\\b",                  // 搜索
            "^find\\b",                  // 查找
            "^pwd$",                     // 当前目录
            "^whoami$",                  // 当前用户
            "^date$",                    // 日期
            "^echo\\b",                  // 输出
            "^df\\b",                    // 磁盘信息
            "^free\\b",                  // 内存信息
            "^top\\b",                   // 进程信息
            "^ps\\b",                    // 进程列表
            "^netstat\\b",               // 网络状态
            "^ss\\b",                    // socket 统计
            "^uname\\b",                 // 系统信息
            "^hostname$",                // 主机名
            "^ip\\s+(addr|route)\\b",    // 网络信息
            "^docker\\s+(ps|logs|inspect|stats)\\b", // Docker 只读
            "^git\\s+(status|log|diff|branch|show)\\b", // Git 只读
            "^systemctl\\s+(status|list)\\b",         // 服务只读
            "^npm\\s+(list|view|outdated)\\b",        // npm 只读
            "^pip\\s+(list|show)\\b",                 // pip 只读
            "^curl\\s+(?!.*\\|)\\b",     // curl（不管道执行）
            "^wget\\s+(?!.*\\|)\\b"      // wget（不管道执行）
    ));

    /** 编译后的正则缓存 */
    private volatile List<Pattern> compiledBlocked;
    private volatile List<Pattern> compiledDangerous;
    private volatile List<Pattern> compiledWarning;
    private volatile List<Pattern> compiledSafe;

    /**
     * 对命令进行安全分级
     */
    public CommandSafety classify(String command) {
        if (command == null || command.isBlank()) {
            return CommandSafety.SAFE;
        }

        String normalized = command.trim().toLowerCase();

        // 1. 黑名单 → BLOCKED
        if (matchesAny(normalized, getCompiledBlocked())) {
            log.debug("[SecurityClassifier] BLOCKED: {}", command);
            return CommandSafety.BLOCKED;
        }

        // 2. 白名单 → SAFE
        if (matchesAny(normalized, getCompiledSafe())) {
            log.debug("[SecurityClassifier] SAFE: {}", command);
            return CommandSafety.SAFE;
        }

        // 3. 危险模式 → DANGEROUS
        if (matchesAny(normalized, getCompiledDangerous())) {
            log.debug("[SecurityClassifier] DANGEROUS: {}", command);
            return CommandSafety.DANGEROUS;
        }

        // 4. 警告模式 → WARNING
        if (matchesAny(normalized, getCompiledWarning())) {
            log.debug("[SecurityClassifier] WARNING: {}", command);
            return CommandSafety.WARNING;
        }

        // 5. 默认 WARNING（未知命令需确认）
        log.debug("[SecurityClassifier] WARNING (default): {}", command);
        return CommandSafety.WARNING;
    }

    private boolean matchesAny(String command, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    private List<Pattern> getCompiledBlocked() {
        if (compiledBlocked == null) {
            compiledBlocked = compilePatterns(blockedPatterns);
        }
        return compiledBlocked;
    }

    private List<Pattern> getCompiledDangerous() {
        if (compiledDangerous == null) {
            compiledDangerous = compilePatterns(dangerousPatterns);
        }
        return compiledDangerous;
    }

    private List<Pattern> getCompiledWarning() {
        if (compiledWarning == null) {
            compiledWarning = compilePatterns(warningPatterns);
        }
        return compiledWarning;
    }

    private List<Pattern> getCompiledSafe() {
        if (compiledSafe == null) {
            compiledSafe = compilePatterns(safePatterns);
        }
        return compiledSafe;
    }

    private List<Pattern> compilePatterns(List<String> patterns) {
        return patterns.stream()
                .map(p -> {
                    try {
                        return Pattern.compile(p, Pattern.CASE_INSENSITIVE);
                    } catch (Exception e) {
                        log.warn("[SecurityClassifier] 无效的正则模式: {}, error: {}", p, e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    // ===== 配置化 setter（允许通过 application.yml 扩展） =====

    public void setBlockedPatterns(List<String> blockedPatterns) {
        this.blockedPatterns = new ArrayList<>(blockedPatterns);
        this.compiledBlocked = null;
    }

    public void setDangerousPatterns(List<String> dangerousPatterns) {
        this.dangerousPatterns = new ArrayList<>(dangerousPatterns);
        this.compiledDangerous = null;
    }

    public void setWarningPatterns(List<String> warningPatterns) {
        this.warningPatterns = new ArrayList<>(warningPatterns);
        this.compiledWarning = null;
    }

    public void setSafePatterns(List<String> safePatterns) {
        this.safePatterns = new ArrayList<>(safePatterns);
        this.compiledSafe = null;
    }
}
