---
name: machine-controller
description: "Control local machine to execute system commands, file operations, process management. Use when: user wants to run commands, read/write files, list directories, manage processes, execute scripts."
metadata:
  openclaw:
    emoji: "💻"
    os: "darwin,linux,windows"
    requires:
      bins: "bash,powershell,python3,python"
---

# Machine Controller

Control local machine to execute system-level tasks through secure command execution, file operations, and process management.

## When to Use

✅ **USE this skill when:**

- User asks to "list directory" or "view files in [path]"
- User asks to "read file" or "show content of [file]"
- User asks to "write file" or "create file at [path]"
- User asks to "run command" or "execute [command]"
- User asks to "manage processes" (list/kill)
- User asks to "check system info" or "get environment variables"

## When NOT to Use

❌ **DON'T use this skill when:**

- Simple questions without action required
- UI/web interactions → use page-agent skill
- Interactive terminal sessions → use tmux skill
- Remote server operations → use ssh skill

## Core Capabilities

### 1. File Operations

| Tool | Description | Windows | Linux/Mac |
|------|-------------|---------|------------|
| list-dir | List directory contents | `dir /b` | `ls -la` |
| read-file | Read file content | `type [file]` | `cat [file]` |
| write-file | Write file content | `echo. [content] > [file]` | `echo [content] > [file]` |
| file-exists | Check if file exists | `if exist [file]` | `test -f [file]` |

### 2. System Command Execution

| Tool | Description | Example |
|------|-------------|---------|
| execute-command | Execute whitelisted commands | `git status`, `npm install` |
| run-script | Run script files | `python script.py`, `bash script.sh` |

### 3. Process Management

| Tool | Description | Windows | Linux/Mac |
|------|-------------|---------|------------|
| list-processes | List running processes | `tasklist` | `ps aux` |
| kill-process | Terminate process | `taskkill /PID [pid]` | `kill [pid]` |

### 4. Environment Information

| Tool | Description | Example |
|------|-------------|---------|
| get-system-info | Get OS/memory/CPU info | `systeminfo` / `uname -a` |
| get-env-vars | Get environment variables | `set` / `env` |

## Common Commands

### List Directory

```bash
# Windows
dir D:\work\code
dir /s /b D:\work\code

# Linux/Mac
ls -la /home/user
ls -R /path/to/dir
```

### Read File

```bash
# Windows
type D:\work\code\pom.xml
Get-Content D:\work\code\pom.xml

# Linux/Mac
cat /path/to/file.txt
head -n 20 /path/to/file.txt
```

### Execute Command

```bash
# Git operations
git status
git log --oneline -5

# Build tools
mvn clean install
npm run build
python3 script.py
```

### Process Management

```bash
# List processes
# Windows
tasklist | findstr java

# Linux/Mac
ps aux | grep java

# Kill process
# Windows
taskkill /PID 1234 /F

# Linux/Mac
kill -9 1234
```

## Cross-Platform Detection

The skill should detect the operating system and use appropriate commands:

```bash
# Detect OS
uname -s  # Returns "Linux", "Darwin", or "Windows_NT"

# Windows-specific
if [ "$OSTYPE" = "windows" ]; then
    dir /b
else
    ls -la
fi
```

## Security Configuration

All executable commands must be defined in `guardrails/allowed-commands.yaml`. Path restrictions apply via `guardrails/blocked-paths.yaml`.

### Allowed Commands

See `guardrails/allowed-commands.yaml`:

- File operations: `ls`, `dir`, `cat`, `type`
- Version control: `git`
- Build tools: `mvn`, `gradle`, `npm`, `pnpm`
- Script interpreters: `python`, `python3`, `node`, `java`
- System info: `uname`, `systeminfo`, `hostname`, `whoami`

### Blocked Paths

See `guardrails/blocked-paths.yaml`:

- System critical directories
- Sensitive user directories
- Temporary directories

## Usage Examples

### Example 1: List Directory

```
User: 帮我看看 D:\work\code 目录下有什么
AI: 使用 list-dir 工具列出目录内容

Command: dir D:\work\code
```

### Example 2: Read File

```
User: 读取一下项目的 pom.xml 文件
AI: 使用 read-file 工具读取文件内容

Command: type D:\work\code\pom.xml
```

### Example 3: Execute Command

```
User: 帮我运行一下 git status
AI: 使用 execute-command 工具执行 git status

Command: git status
```

### Example 4: Cross-Platform

```
User: 查看当前系统信息
AI: 检测操作系统并获取系统信息

Windows: systeminfo
Linux/Mac: uname -a && free -h && df -h
```

## Script Output Format

All scripts should output JSON format for easy parsing:

```json
{
  "status": "success",
  "command": "ls -la",
  "output": "...",
  "exitCode": 0
}
```

```json
{
  "status": "error",
  "message": "Command not allowed",
  "exitCode": 1
}
```

## Notes

- **Security First**: All commands executed under whitelist control
- **Timeout Protection**: Long-running commands auto-terminate after 30s
- **Error Handling**: Detailed error messages returned on failure
- **Audit Logging**: All operations logged for security review
- **Cross-Platform**: Automatic OS detection and command adaptation