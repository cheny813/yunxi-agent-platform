# 配置指南（快速摘要）

本文件是配置快速摘要。完整的配置项说明、权限模式、模型路由、工具箱裁剪等详见 [docs/guide/06-configuration.md](docs/guide/06-configuration.md)。所有敏感信息（API 密钥、密码等）均通过环境变量或外部配置注入，不硬编码。

## 配置体系

平台采用「统一配置中心」：`yunxi-config` 模块持有全部运行时参数，应用通过 `yunxi-app/src/main/resources/application.yml` 中的 `spring.config.import` 拉入。

配置片段位于 `yunxi-config/src/main/resources/config/`，由 `imports.yml` 聚合：

| 片段 | 作用 |
|------|------|
| `server.yml` | 端口与基础 Web 服务 |
| `redis.yml` | Redis 连接（`spring.data.redis.*`） |
| `models.yml` | 模型目录与路由（`yunxi.models`） |
| `mcp.yml` | MCP 服务注册 |
| `security.yml` | 访问认证 |
| `a2a.yml` | A2A 协议暴露 |
| `agentscope.yml` | 框架参数 |
| `observability.yml` | 追踪 / 指标 |

配置优先级：**环境变量 > 配置片段 > 片段内默认值**。

## 本地开发快速配置

启动脚本 `start-yunxi.ps1` 已内置本地开发默认值，绝大多数场景直接运行即可：

```powershell
.\start-yunxi.ps1          # 自动打包并启动（端口 40001）
.\start-yunxi.ps1 -Web     # 同时拉起前端（http://localhost:5173）
```

仅需按需覆盖以下环境变量（PowerShell 语法）：

### 大模型密钥（对话必需）

```powershell
$env:DASHSCOPE_API_KEY = "sk-你的真实Key"
```

缺失时应用仍可启动，仅对话失败。切换其它厂商在 `config/models.yml` 的 `yunxi.models.catalog` 新增 provider 条目，并把 `defaults.llm` 指向它。

### Redis（会话状态 / 轨迹 / 跨实例锁，必需）

Docker 部署默认密码 `redispass`；非 Docker 用环境变量覆盖：

```powershell
$env:REDIS_HOST = "127.0.0.1"
$env:REDIS_PORT = "6379"
$env:REDIS_PASSWORD = "redispass"
$env:REDIS_DATABASE = "0"
```

### Milvus（可选，仅 mcp-milvus 向量检索类工具需要）

```powershell
$env:MILVUS_HOST = "127.0.0.1"
$env:MILVUS_PORT = "19530"
$env:MILVUS_USERNAME = "root"
$env:MILVUS_PASSWORD = "Milvus"
```

### aistio 管控面（可选）

```powershell
$env:YUNXI_AISTIO_ENABLED = "true"
$env:YUNXI_AISTIO_CONTROL_PLANE_URL = "http://localhost:18081"
$env:YUNXI_AISTIO_BASE_URL = "http://localhost:40001"
$env:YUNXI_AISTIO_INTERNAL_TOKEN = "compose-local-internal-token-at-least-32chars"
```

## 关于 MySQL

平台应用本身**不连接 MySQL**——会话状态走 Redis，轨迹走 `TraceStore`（Redis 实现）。

`deploy/infra` 中的 MySQL 容器仅服务于独立的 `mcp-nutrition` MCP 服务（库 `yunxi_nutrition`，由 `deploy/infra/init/nutrition-schema.sql` 初始化表结构）。不运行 mcp-nutrition 时无需 MySQL。

## 租户级 Agent 分配（Per-Tenant Allocation）

yunxi 提供「租户 → Agent」分配能力，用于多租户场景下按租户隔离可被调用的 Agent。分配关系以独立 Nacos dataId 持久化，与 Agent 定义全量快照隔离，保证分配的单一真相源独立可演进。

### 存储与配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `yunxi.config.nacos.allocation-data-id` | `yunxi.tenant-agent-allocation.json` | 租户分配快照 dataId |
| `yunxi.config.nacos.group` | `YUNXI_CONFIG_GROUP` | 分组（与 Agent 定义共享） |
| `yunxi.config.nacos.namespace` | `public` | 命名空间 |

当 `yunxi.config.nacos.enabled=false`（默认）时，ConfigCenter 退化为本地内存，分配关系由启动配置注入、不持久化。

dataId 内容为 JSON 映射：`{ "<tenantId>": ["<agentId>", ...] }`。示例：

```json
{
  "tenant-a": ["nutritionist", "weather-assistant"],
  "tenant-b": ["finance-analyst"]
}
```

支持热加载：修改并发布 dataId 后，运行中的实例经 Nacos 监听自动刷新，无需重启。

> 前提：本能力仅在 `yunxi.config.nacos.enabled=true` 时生效（分配持久化到 Nacos）。默认 `false` 时退化为本地内存，分配由启动配置注入，不存在 Nacos 控制台这一层。

### 在 Nacos 控制台配置分配

启用 Nacos 后，分配的"允许名单"就是 Nacos 里 **dataId = `yunxi.tenant-agent-allocation.json`** 的配置内容（与 Agent 定义快照分属不同 dataId，互不干扰）。两种写入方式最终都落到同一个 dataId：

- **方式一 · 控制台手写**（运维常用）：直接在 Nacos 控制台编辑并发布该 dataId。
- **方式二 · SDK 写入**（程序调用）：经 admin 接口或启动注入调用 `registerTenantAllocation(...)`，由代码用 Nacos SDK 写回同一 dataId（二者内容格式一致）。

控制台操作步骤：

1. 打开 Nacos 控制台 → **配置管理 → 配置列表**；
2. 在右上角切换 **命名空间 = `public`**（或你配置的 `yunxi.config.nacos.namespace`），并在分组筛选中选择 **`YUNXI_CONFIG_GROUP`**（或你配置的 `yunxi.config.nacos.group`）；
3. 找到 dataId = `yunxi.tenant-agent-allocation.json`（不存在则**新建**：Data ID 填该值、Group 填上面分组、配置格式选 `JSON`）；
4. 在配置内容中填写允许名单 JSON 并**发布**：
   ```json
   {
     "tenant-a": ["nutritionist", "weather-assistant"],
     "tenant-b": ["finance-analyst"]
   }
   ```
5. 运行中实例经监听器在数秒内自动热加载，无需重启。

**"有记录 / 无记录"如何判断（即拒绝边界）**：完全由这份 JSON 里**该租户 key 是否存在**决定，而非任何控制台开关——

- 租户 key **存在**（值即使是 `["nutritionist"]`）→ 视为"有记录"，仅放行数组内的 Agent；
- 某租户**整个 key 都删掉** → 视为"无记录"，走 fail-open（放行全量目录，向后兼容）；
- 某租户值为 **`[]`（显式空数组）** → 仍算"有记录"，表示 allow-none（拒绝该租户取用任何 Agent）。

### 运行时语义

- **四协议入口强制校验**：web / Chat Completions / A2A / AG-UI 任一协议在解析目标 Agent 前，均调用 `TenantAgentAllocationService.assertAllowed(tenantId, agentId)`；越权请求以 `404 NO_AGENT` 拒绝。
- **租户解析**：经请求头（如 `X-Tenant-Id`）解析租户，未携带时回落到默认租户配置。
- **fail-open**：某租户在分配表中无记录时按「全量目录」放行（不拒绝任何既有全局 Agent）；仅当租户有记录但其目标 Agent 不在集合内才拒绝。

### 运维端点

- `GET /v1/admin/tenants/{tenantId}/agents`：返回指定租户被授权 Agent 的简要身份（`agentId` / `name` / `description`）。该端点受全局认证保护（未认证返回 `401`），`{tenantId}` 由路径显式传入、不依赖请求上下文租户。
- 返回内容由「分配集合 ∩ 当前 Agent 定义目录」组装；无分配时返回全量目录（fail-open）。

## MUSE 技能使用遥测（可选）

MUSE 自进化的剪枝体检默认依赖模型自报使用次数；开启服务端真相后，技能使用事件落 JDBC 表（与技能仓库同库），剪枝时以聚合结果覆盖自报值，并激活「使用者广度」画像判据。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `yunxi.muse.usage.enabled` | 跟随 `yunxi.skill.jdbc.enabled` | 技能使用事件遥测开关。技能仓库启用则自动启用（同一数据源同一生命周期）；显式设置可否决——`false` 单独关闭，`true` 在数据面缺失时启动报错 |

事件表 `skill_usage_event` 由存储启动期自动创建（幂等）；`deploy/infra/init/skill-store.sql` 为部署期显式管控兜底。

## 生产部署

- 密钥一律走环境变量 / Secrets，切勿提交明文。
- Docker / Kubernetes 编排见 `deploy/` 目录（含 `infra` 与 `aistio`）。
- 通过网关或环境变量注入真实大模型密钥与管理员口令，不使用脚本内置的 dev 默认值。

## 故障排除

- **配置不生效**：确认环境变量已设置、配置片段已加入 `imports.yml`、文件编码为 UTF-8。
- **Redis 连接失败（`NOAUTH`）**：密码与 `deploy/infra/docker-compose.yml` 的 `--requirepass` 不一致，用 `REDIS_PASSWORD` 覆盖。
- **密钥错误**：确认 `DASHSCOPE_API_KEY` 正确且网络可达。
