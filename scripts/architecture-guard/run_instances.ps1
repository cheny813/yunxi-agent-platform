# 用环境变量（必被 Spring 读取）强制激活 RedisConfig(JSON 序列化器) 并启动两个实例，
# 对接已在运行的 Docker 基础服务（Redis/MySQL/Nacos/Milvus 均发布到宿主机 127.0.0.1）。
$ErrorActionPreference = "Stop"
$env:CONVERSATION_STORAGE_TYPE = "redis"
$env:CONVERSATION_TRACE_STORAGE_TYPE = "redis"
$env:REDIS_HOST = "127.0.0.1"; $env:REDIS_PORT = "6379"; $env:REDIS_PASSWORD = "redispass"
$env:MYSQL_HOST = "127.0.0.1"; $env:MYSQL_PORT = "3306"; $env:MYSQL_USERNAME = "root"; $env:MYSQL_PASSWORD = "root"; $env:MYSQL_DATABASE = "yunxi_agent_platform"
$env:MILVUS_HOST = "127.0.0.1"; $env:MILVUS_PORT = "19530"

$env:SERVER_PORT = "40011"
Start-Process -FilePath java -WorkingDirectory "d:\work\code\yunxi-agent-platform" `
  -ArgumentList "-jar","agent-app/target/agent-app-2.0.3.jar" `
  -RedirectStandardOutput "d:\work\code\yunxi-agent-platform\scripts\architecture-guard\app1.log" `
  -RedirectStandardError "d:\work\code\yunxi-agent-platform\scripts\architecture-guard\app1.err" `
  -WindowStyle Hidden
Write-Host "LAUNCHED_APP1"

$env:SERVER_PORT = "40012"
Start-Process -FilePath java -WorkingDirectory "d:\work\code\yunxi-agent-platform" `
  -ArgumentList "-jar","agent-app/target/agent-app-2.0.3.jar" `
  -RedirectStandardOutput "d:\work\code\yunxi-agent-platform\scripts\architecture-guard\app2.log" `
  -RedirectStandardError "d:\work\code\yunxi-agent-platform\scripts\architecture-guard\app2.err" `
  -WindowStyle Hidden
Write-Host "LAUNCHED_APP2"
