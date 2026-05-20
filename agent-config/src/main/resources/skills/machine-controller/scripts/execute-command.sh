#!/bin/bash
# 执行系统命令脚本
# 用法: ./execute-command.sh <命令> [参数...]
# 输出: JSON 格式

# 检查参数
if [ -z "$1" ]; then
    echo "{\"status\": \"error\", \"message\": \"请指定要执行的命令\"}"
    exit 1
fi

COMMAND="$1"
shift

# 检查命令是否在白名单中（简化版本）
# 实际生产环境应该读取 allowed-commands.yaml 进行验证
WHITELIST="ls,dir,cat,type,git,python,python3,node,java,mvn,gradle,npm,pnpm,yarn,pip,docker,curl,ping"

# 简单的白名单检查
FOUND=0
IFS=',' read -ra COMMANDS <<< "$WHITELIST"
for cmd in "${COMMANDS[@]}"; do
    if [ "$COMMAND" = "$cmd" ]; then
        FOUND=1
        break
    fi
done

if [ "$FOUND" -eq 0 ]; then
    echo "{\"status\": \"error\", \"message\": \"命令不在白名单中: $COMMAND\"}"
    exit 1
fi

# 执行命令，设置超时
timeout 30 "$COMMAND" "$@" > /tmp/cmd_stdout.txt 2> /tmp/cmd_stderr.txt
EXIT_CODE=$?

STDOUT=$(cat /tmp/cmd_stdout.txt 2>/dev/null | head -500)
STDERR=$(cat /tmp/cmd_stderr.txt 2>/dev/null | head -500)

# 清理临时文件
rm -f /tmp/cmd_stdout.txt /tmp/cmd_stderr.txt

if [ $EXIT_CODE -eq 0 ]; then
    # 转义输出
    STDOUT_ESC=$(echo "$STDOUT" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n' '\\n')
    echo "{\"status\": \"success\", \"command\": \"$COMMAND\", \"stdout\": \"$STDOUT_ESC\", \"exitCode\": $EXIT_CODE}"
else
    STDERR_ESC=$(echo "$STDERR" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n' '\\n')
    echo "{\"status\": \"error\", \"command\": \"$COMMAND\", \"stderr\": \"$STDERR_ESC\", \"exitCode\": $EXIT_CODE}"
fi