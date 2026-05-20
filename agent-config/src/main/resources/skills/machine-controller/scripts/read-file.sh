#!/bin/bash
# 读取文件内容脚本
# 用法: ./read-file.sh <文件路径>
# 输出: JSON 格式

FILE_PATH="$1"

# 检查文件是否存在
if [ -z "$FILE_PATH" ]; then
    echo "{\"status\": \"error\", \"message\": \"请指定文件路径\"}"
    exit 1
fi

if [ ! -f "$FILE_PATH" ]; then
    echo "{\"status\": \"error\", \"message\": \"文件不存在: $FILE_PATH\"}"
    exit 1
fi

# 检查文件大小
FILE_SIZE=$(wc -c < "$FILE_PATH")
MAX_SIZE=10485760  # 10MB

if [ "$FILE_SIZE" -gt "$MAX_SIZE" ]; then
    echo "{\"status\": \"error\", \"message\": \"文件过大: $FILE_SIZE bytes (最大 10MB)\"}"
    exit 1
fi

# 读取文件内容并转换为 JSON 安全格式
CONTENT=$(cat "$FILE_PATH" | head -1000)
# 使用 Python 或其他工具进行 JSON 转义
if command -v python3 &> /dev/null; then
    CONTENT_JSON=$(python3 -c "import json,sys; print(json.dumps(sys.stdin.read()))" <<< "$CONTENT")
    echo "{\"status\": \"success\", \"path\": \"$FILE_PATH\", \"size\": $FILE_SIZE, \"content\": $CONTENT_JSON}"
elif command -v python &> /dev/null; then
    CONTENT_JSON=$(python -c "import json,sys; print(json.dumps(sys.stdin.read()))" <<< "$CONTENT")
    echo "{\"status\": \"success\", \"path\": \"$FILE_PATH\", \"size\": $FILE_SIZE, \"content\": $CONTENT_JSON}"
else
    # 简单转义
    CONTENT_ESCAPED=$(echo "$CONTENT" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g' | tr '\n' '\\n')
    echo "{\"status\": \"success\", \"path\": \"$FILE_PATH\", \"size\": $FILE_SIZE, \"content\": \"$CONTENT_ESCAPED\"}"
fi