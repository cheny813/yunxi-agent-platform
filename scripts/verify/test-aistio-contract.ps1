<#
.SYNOPSIS
  yunxi <-> aistio 外部应用集成契约校验
.DESCRIPTION
  验证 yunxi 作为 aistio External application 的集成健康度：
  - 注册 / 心跳 / 健康保活（控制台显示 ready/active/available 的依据）
  - yunxi 数据面契约端点 /agentscope/health|info|sessions 是否可达
  - aistio 侧是否把该 agent 列为可对话（chat-agents capability 状态）
  所有运行输出写入 tmp/aistio-verify/（已被 .gitignore 忽略），不污染仓库根。
.PARAMETER AistioBase
  aistio 控制面地址，默认 http://127.0.0.1:8081
.PARAMETER YunxiBase
  yunxi 数据面地址（即 aistio 注册时的 base-url），默认 http://127.0.0.1:40001
.PARAMETER AgentId
  yunxi-agent-platform 在 aistio 目录中的 agent id
.PARAMETER InternalToken
  aistio 内部令牌，用于访问受保护的 /agentscope/* 端点
.PARAMETER AdminUser / AdminPass
  aistio 控制台登录账号，用于换取 Bearer 令牌
#>
param(
  [string]$AistioBase  = 'http://127.0.0.1:8081',
  [string]$YunxiBase   = 'http://127.0.0.1:40001',
  [string]$AgentId     = '5335bc02-cf05-431b-8416-44a40c26daef',
  [string]$InternalToken = 'compose-local-internal-token-at-least-32chars',
  [string]$AdminUser   = 'admin',
  [string]$AdminPass   = 'admin'
)

$ErrorActionPreference = 'Stop'
$outDir = Join-Path $PSScriptRoot '..\..\tmp\aistio-verify'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
$outDir = Resolve-Path $outDir

function Log($m) { Write-Host $m }

# 1) aistio 登录换 Bearer
$token = (Invoke-RestMethod -Uri "$AistioBase/api/auth/login" -Method Post -ContentType application/json `
  -Body ("{""username"":""$AdminUser"",""password"":""$AdminPass""}")).token
$h = @{'Authorization' = "Bearer $token" }
Log "==> aistio 登录 OK"

# 2) aistio 侧：agent 目录能力上报
$agent = Invoke-RestMethod -Uri "$AistioBase/api/v1/agents/$AgentId" -Headers $h
$agent | ConvertTo-Json -Depth 6 | Out-File (Join-Path $outDir 'agent.json') -Encoding ascii
Log ("==> agent capabilities: " + ($agent.agent.capabilities -join ', '))

# 3) aistio 侧：控制台建聊时用的 chat-agents 列表（看 conversation 能力是否 available）
$chatAgents = Invoke-RestMethod -Uri "$AistioBase/api/v1/chat-agents" -Headers $h
$chatAgents | ConvertTo-Json -Depth 6 | Out-File (Join-Path $outDir 'chat-agents.json') -Encoding ascii
$yx = $chatAgents.items | Where-Object { $_.id -eq $AgentId }
if ($yx) { Log ("==> chat-agents capability: state=$($yx.capability.state) reason=$($yx.capability.reason)") }
else { Log '==> yunxi-agent-platform 未出现在 chat-agents 列表' }

# 4) 直连 yunxi 数据面契约端点
$itokH = @{'X-Builder-Internal-Token' = $InternalToken }
$r = @{}
$r.health   = try { (Invoke-WebRequest -Uri "$YunxiBase/agentscope/health" -UseBasicParsing).Content } catch { "ERR:$_" }
$r.info     = try { (Invoke-WebRequest -Uri "$YunxiBase/agentscope/info" -Headers $itokH -UseBasicParsing).Content } catch { "ERR:$_" }
$r.sessions = try { (Invoke-WebRequest -Uri "$YunxiBase/agentscope/sessions" -Headers $itokH -UseBasicParsing).Content } catch { "ERR:$_" }
$r | ConvertTo-Json | Out-File (Join-Path $outDir 'contract-direct.json') -Encoding ascii
Log "==> /agentscope/health : $($r.health)"
Log "==> /agentscope/info   : $($r.info)"
Log "==> /agentscope/sessions: $($r.sessions)"

Log "==> 输出已写入 $outDir"
