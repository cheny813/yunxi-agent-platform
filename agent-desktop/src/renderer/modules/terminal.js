/**
 * terminal.js — 终端面板逻辑
 *
 * 包含：中文命令解析、命令执行、快捷操作
 * 依赖 ctx: { el, addOutput, addActivity, escapeHtml }
 */

export function initTerminal(ctx) {
    const { el, addOutput, addActivity } = ctx;

    // ==================== 中文描述→命令解析 ====================

    function parseChineseCommand(input) {
        const s = input.trim();

        const patterns = [
            // --- 目录与文件 ---
            [/当前目录|我在哪|当前路径|工作目录|pwd/, () => ({ cmd: 'echo', args: ['%cd%'], display: '查看当前目录 → echo %cd%' })],
            [/切换目录|进入目录|跳转到|cd\s+(.+)/, (m) => ({ cmd: 'cd', args: [m[1] || s.replace(/切换目录|进入目录|跳转到/g, '').trim()], display: `切换目录 → cd ${m[1] || s.replace(/切换目录|进入目录|跳转到/g, '').trim()}` })],
            [/列出文件|查看目录|目录内容|dir|ls/, () => ({ cmd: 'dir', args: [], display: '列出文件 → dir' })],
            [/查看(.+)文件|读取(.+)文件|打开(.+)文件|cat\s+(.+)/, (m) => ({ cmd: 'type', args: [m[1] || m[2] || m[3] || m[4] || ''], display: `查看文件 → type ${m[1] || m[2] || m[3] || m[4] || ''}` })],
            [/删除文件(.+)|rm\s+(.+)/, (m) => ({ cmd: 'del', args: [m[1] || m[2] || ''], display: `删除文件 → del ${m[1] || m[2] || ''}` })],

            // --- Git ---
            [/git状态|查看状态|仓库状态|git status/, () => ({ cmd: 'git', args: ['status'], display: 'Git 状态 → git status' })],
            [/拉取代码|拉代码|git pull|更新代码/, () => ({ cmd: 'git', args: ['pull'], display: '拉取代码 → git pull' })],
            [/推送代码|推代码|git push|提交远程/, () => ({ cmd: 'git', args: ['push'], display: '推送代码 → git push' })],
            [/提交(.+)|commit\s+(.+)/, (m) => ({ cmd: 'git', args: ['commit', '-m', m[1] || m[2] || 'update'], display: `提交 → git commit -m "${m[1] || m[2] || 'update'}"` })],
            [/查看日志|提交记录|git log/, () => ({ cmd: 'git', args: ['log', '--oneline', '-10'], display: '查看日志 → git log --oneline -10' })],
            [/查看分支|分支列表|git branch/, () => ({ cmd: 'git', args: ['branch', '-a'], display: '查看分支 → git branch -a' })],

            // --- Maven ---
            [/运行测试|跑测试|执行测试|mvn test|跑单测/, () => ({ cmd: 'mvn', args: ['test'], display: '运行测试 → mvn test' })],
            [/构建|编译打包|打包|mvn package|mvn build/, () => ({ cmd: 'mvn', args: ['clean', 'package', '-DskipTests'], display: '构建 → mvn clean package -DskipTests' })],
            [/maven清理|mvn clean|清理构建/, () => ({ cmd: 'mvn', args: ['clean'], display: '清理 → mvn clean' })],
            [/编译|mvn compile/, () => ({ cmd: 'mvn', args: ['compile'], display: '编译 → mvn compile' })],
            [/跑指定测试|运行(.+)测试/, (m) => {
                const testClass = m[1] ? m[1].trim() : '';
                return testClass
                    ? { cmd: 'mvn', args: ['test', `-Dtest=${testClass}`], display: `跑指定测试 → mvn test -Dtest=${testClass}` }
                    : { cmd: 'mvn', args: ['test'], display: '运行测试 → mvn test' };
            }],
            [/安装依赖|mvn install/, () => ({ cmd: 'mvn', args: ['install', '-DskipTests'], display: '安装 → mvn install -DskipTests' })],

            // --- npm ---
            [/npm安装|安装npm依赖|npm install|装依赖/, () => ({ cmd: 'npm', args: ['install'], display: '安装依赖 → npm install' })],
            [/npm启动|启动前端|npm start|npm run dev/, () => ({ cmd: 'npm', args: ['run', 'dev'], display: '启动 → npm run dev' })],
            [/npm构建|前端打包|npm build/, () => ({ cmd: 'npm', args: ['run', 'build'], display: '构建 → npm run build' })],

            // --- 系统 ---
            [/系统信息|电脑信息|系统版本|uname|systeminfo/, () => ({ cmd: 'systeminfo', args: [], display: '系统信息 → systeminfo' })],
            [/网络|ip地址|ipconfig|ifconfig/, () => ({ cmd: 'ipconfig', args: [], display: '网络信息 → ipconfig' })],
            [/端口(.+)|查看端口|netstat/, (m) => {
                const port = m[1] ? m[1].trim() : '';
                return port
                    ? { cmd: 'netstat', args: ['-ano', '|', 'findstr', port], display: `查看端口 → netstat -ano | findstr ${port}` }
                    : { cmd: 'netstat', args: ['-ano'], display: '查看端口 → netstat -ano' };
            }],
            [/进程|任务列表|tasklist/, () => ({ cmd: 'tasklist', args: [], display: '进程列表 → tasklist' })],
            [/杀进程|结束进程|taskkill/, () => ({ cmd: 'tasklist', args: [], display: '请先用"进程"查看 PID，再用 taskkill /PID <pid> /F 结束' })],
            [/磁盘|硬盘空间|磁盘使用/, () => ({ cmd: 'wmic', args: ['logicaldisk', 'get', 'size,freespace,caption'], display: '磁盘空间 → wmic logicaldisk get size,freespace,caption' })],
            [/环境变量|查看环境/, () => ({ cmd: 'set', args: [], display: '环境变量 → set' })],

            // --- Java ---
            [/java版本|jdk版本|java -version/, () => ({ cmd: 'java', args: ['-version'], display: 'Java 版本 → java -version' })],
            [/node版本|node -v/, () => ({ cmd: 'node', args: ['-v'], display: 'Node 版本 → node -v' })],
            [/maven版本|mvn -v|mvn版本/, () => ({ cmd: 'mvn', args: ['-v'], display: 'Maven 版本 → mvn -v' })],
            [/git版本|git --version/, () => ({ cmd: 'git', args: ['--version'], display: 'Git 版本 → git --version' })],
        ];

        for (const [regex, resolver] of patterns) {
            const match = s.match(regex);
            if (match) return resolver(match);
        }
        return null;
    }

    function isChineseDescription(input) {
        if (/[\u4e00-\u9fff]/.test(input)) return true;
        const commandPrefixes = ['git', 'mvn', 'npm', 'node', 'java', 'python', 'pip',
            'cd', 'dir', 'ls', 'cat', 'cp', 'mv', 'rm', 'mkdir', 'echo', 'set',
            'ping', 'curl', 'wget', 'docker', 'kubectl', 'ssh', 'scp',
            'tasklist', 'netstat', 'ipconfig', 'systeminfo', 'where', 'which',
            'gradle', 'cargo', 'go ', 'dotnet', 'yarn', 'pnpm', 'npx'];
        const firstWord = input.split(/\s+/)[0].toLowerCase();
        return !commandPrefixes.includes(firstWord) && input.length > 4;
    }

    async function executeCustomCommand() {
        const cmdStr = el.customCommand.value.trim();
        if (!cmdStr) return;
        el.customCommand.value = '';

        let command, args, displayCmd;

        if (!isChineseDescription(cmdStr)) {
            const parts = cmdStr.split(' ');
            command = parts[0];
            args = parts.slice(1);
            displayCmd = cmdStr;
            addOutput({ status: 'success', command: displayCmd, message: '执行中...' });
        } else {
            const parsed = parseChineseCommand(cmdStr);
            if (parsed) {
                command = parsed.cmd;
                args = parsed.args;
                displayCmd = parsed.display;
                addOutput({ status: 'success', command: displayCmd, message: '执行中...' });
            } else {
                addOutput({ status: 'success', command: cmdStr, message: 'AI 解析中...' });
                try {
                    const aiResult = await window.agentAPI.parseCommand(cmdStr);
                    if (aiResult.success) {
                        const aiCmd = aiResult.command;
                        const aiParts = aiCmd.split(' ');
                        command = aiParts[0];
                        args = aiParts.slice(1);
                        displayCmd = `${cmdStr} → ${aiCmd}`;
                        addOutput({ status: 'success', command: displayCmd, message: 'AI 已解析，执行中...' });
                    } else {
                        addOutput({ status: 'error', command: cmdStr, message: `AI 解析失败(${aiResult.error})，请直接输入命令或尝试更简单的描述` });
                        return;
                    }
                } catch (err) {
                    addOutput({ status: 'error', command: cmdStr, message: 'AI 服务不可用，请直接输入命令或尝试更简单的描述' });
                    return;
                }
            }
        }

        try {
            const result = await window.agentAPI.executeCommand(command, args);
            addOutput(result);
        } catch (err) {
            addOutput({ status: 'error', command: displayCmd, message: err.message });
        }
    }

    // ==================== 事件绑定 ====================

    el.btnClearOutput.addEventListener('click', () => {
        el.outputContent.innerHTML = '<div class="placeholder">等待命令执行...</div>';
    });

    el.btnExecute.addEventListener('click', executeCustomCommand);
    el.customCommand.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') executeCustomCommand();
    });

    // 快捷操作
    document.querySelectorAll('[data-action]').forEach(btn => {
        btn.addEventListener('click', async () => {
            const action = btn.dataset.action;
            try {
                let result;
                switch (action) {
                    case 'git-status':
                        result = await window.agentAPI.gitOperation('status', []);
                        break;
                    case 'git-pull':
                        result = await window.agentAPI.gitOperation('pull', ['origin', 'main']);
                        break;
                    case 'run-test':
                        result = await window.agentAPI.executeCommand('mvn', ['test']);
                        break;
                    case 'mvn-build':
                        result = await window.agentAPI.executeCommand('mvn', ['clean', 'package', '-DskipTests']);
                        break;
                    case 'npm-install':
                        result = await window.agentAPI.executeCommand('npm', ['install']);
                        break;
                    case 'mvn-clean':
                        result = await window.agentAPI.executeCommand('mvn', ['clean']);
                        break;
                    case 'system-info':
                        const info = await window.agentAPI.getSystemInfo();
                        addOutput({
                            status: 'success', command: 'system-info',
                            stdout: `应用: ${info.name} v${info.version}\n平台: ${info.platform} (${info.arch})\nNode: ${info.nodeVersion}\nElectron: ${info.electronVersion}\nChrome: ${info.chromeVersion}`
                        });
                        return;
                    default:
                        addOutput({ status: 'error', command: action, message: '未知操作' });
                        return;
                }
                if (result) addOutput(result);
            } catch (err) {
                addOutput({ status: 'error', command: action, message: err.message });
            }
        });
    });

    return { executeCustomCommand, parseChineseCommand };
}
