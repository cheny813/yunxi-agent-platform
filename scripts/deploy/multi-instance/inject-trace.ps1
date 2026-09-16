# 合成轨迹注入器：把一条 ReasoningSpan 轨迹写入 Redis（yunxi:trace:<traceId>），
# 供多实例演练验证「跨实例轨迹共享 + 双实例 HTTP 回放一致」。
# 序列化格式与 RedisConfig 的 GenericJackson2JsonRedisSerializer 对齐（含 @class）。
$ErrorActionPreference = "Stop"
$REDIS_PW = "redispass"
# 逻辑库号与全局配置保持一致：REDIS_DATABASE 环境变量（默认 0，见 config/redis.yml）
$REDIS_DB = if ($env:REDIS_DATABASE) { $env:REDIS_DATABASE } else { 0 }
$TRACE_ID = "drill-001"
$KEY = "yunxi:trace:$TRACE_ID"

# 五段快照：TEXT 开窗/增量/关窗 + TOOL_CALL 开窗/关窗
$spans = @(
  '{"@class":"io.yunxi.platform.trace.ReasoningSpan","traceId":"drill-001","stableKey":"TEXT|u1|s1|m1","eventId":"e1","kind":"TEXT","agentPath":null,"depth":0,"parentKey":null,"phase":null,"snapshot":"OPEN","delta":null,"status":null,"durationMs":null,"payload":null,"at":1000}',
  '{"@class":"io.yunxi.platform.trace.ReasoningSpan","traceId":"drill-001","stableKey":"TEXT|u1|s1|m1","eventId":"e2","kind":"TEXT","agentPath":null,"depth":0,"parentKey":null,"phase":null,"snapshot":"DELTA","delta":"Hello from cross-instance trace","status":null,"durationMs":null,"payload":null,"at":1001}',
  '{"@class":"io.yunxi.platform.trace.ReasoningSpan","traceId":"drill-001","stableKey":"TEXT|u1|s1|m1","eventId":"e3","kind":"TEXT","agentPath":null,"depth":0,"parentKey":null,"phase":null,"snapshot":"CLOSED","delta":null,"status":null,"durationMs":null,"payload":null,"at":1002}',
  '{"@class":"io.yunxi.platform.trace.ReasoningSpan","traceId":"drill-001","stableKey":"TOOL_CALL|u1|s1|b1","eventId":"e4","kind":"TOOL_CALL","agentPath":null,"depth":0,"parentKey":null,"phase":null,"snapshot":"OPEN","delta":null,"status":null,"durationMs":null,"payload":{"@class":"java.util.LinkedHashMap","toolCallId":"b1","toolName":"calculator","args":"{\"x\":40,\"y\":2}"},"at":1003}',
  '{"@class":"io.yunxi.platform.trace.ReasoningSpan","traceId":"drill-001","stableKey":"TOOL_CALL|u1|s1|b1","eventId":"e5","kind":"TOOL_CALL","agentPath":null,"depth":0,"parentKey":null,"phase":null,"snapshot":"CLOSED","delta":null,"status":"success","durationMs":123,"payload":{"@class":"java.util.LinkedHashMap","toolCallId":"b1","toolName":"calculator","result":"42","resultPhase":"end"},"at":1004}'
)

# 先清空旧数据，保证可重复演练
redis-cli -a $REDIS_PW -n $REDIS_DB DEL $KEY | Out-Null

$tmp = New-TemporaryFile
foreach ($s in $spans) {
    Set-Content -Path $tmp.FullName -Value $s -Encoding utf8
    Get-Content -Path $tmp.FullName -Encoding utf8 | redis-cli -a $REDIS_PW -n $REDIS_DB -x RPUSH $KEY | Out-Null
}
Remove-Item $tmp.FullName

$len = (redis-cli -a $REDIS_PW -n $REDIS_DB LLEN $KEY)
Write-Host "INJECTED trace=$TRACE_ID key=$KEY spans=$len"
