#!/bin/bash
# 列出目录内容脚本
# 用法: ./list-dir.sh <目录路径>
# 输出: JSON 格式

# 获取目录参数
DIR_PATH="${1:-.}"

# 检查目录是否存在
if [ ! -d "$DIR_PATH" ]; then
    echo "{\"status\": \"error\", \"message\": \"目录不存在: $DIR_PATH\"}"
    exit 1
fi

# 使用 PowerShell 风格的输出（跨平台）
if [ "$2" = "--json" ]; then
    # JSON 格式输出
    FILES=$(ls -1 "$DIR_PATH" 2>/dev/null | head -100 | tr '\n' ',' | sed 's/,$//')
    echo "{\"status\": \"success\", \"path\": \"$DIR_PATH\", \"files\": [$FILES], \"count\": $(ls -1 "$DIR_PATH" 2>/dev/null | wc -l)}"
else
    # 人类可读格式
    ls -la "$DIR_PATH"
fi