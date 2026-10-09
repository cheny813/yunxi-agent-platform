# 08. 部署指南

生产环境部署与运维指南。

## 部署架构理论

### 什么是部署架构

**部署架构**定义了软件系统如何在硬件环境中运行和分布。

**关键考虑因素**：
| 因素 | 说明 | 影响 |
|------|------|------|
| **可用性** | 系统持续可用的时间 | 99.9% vs 99.99% |
| **可扩展性** | 处理增长负载的能力 | 水平/垂直扩展 |
| **容错性** | 故障时的恢复能力 | 自动故障转移 |
| **性能** | 响应时间和吞吐量 | 用户体验 |
| **成本** | 硬件和运维成本 | 预算控制 |

### 部署模式对比

| 模式 | 说明 | 适用场景 |
|------|------|----------|
| **单机部署** | 所有组件在一台服务器 | 开发测试、小流量 |
| **主从部署** | 主节点+备份节点 | 中等可用性要求 |
| **集群部署** | 多节点负载均衡 | 高可用、高并发 |
| **微服务** | 服务独立部署 | 大型复杂系统 |

---

## 部署架构

### 单机部署

```
┌─────────────────────────────────────┐
│           单服务器                   │
│  ┌─────────┐ ┌─────────┐           │
│  │  Nginx  │ │  MySQL  │           │
│  └────┬────┘ └─────────┘           │
│       │                             │
│  ┌────┴─────────────────────────┐   │
│  │      yunxi Agent Platform      │   │
│  │      (端口 40001)              │   │
│  └───────────────────────────────┘   │
└─────────────────────────────────────┘
```

**适用场景**：日活用户 < 1000，开发测试环境

**优缺点**：
| 优点 | 缺点 |
|------|------|
| 部署简单 | 单点故障 |
| 成本低 | 无法水平扩展 |
| 易于调试 | 性能瓶颈 |

### 集群部署

```
┌─────────────────────────────────────┐
│           负载均衡层                 │
│            Nginx / SLB              │
└─────────────────────────────────────┘
                   │
    ┌──────────────┼──────────────┐
    ▼              ▼              ▼
┌────────┐   ┌────────┐   ┌────────┐
│ Node 1 │   │ Node 2 │   │ Node 3 │
│ 40001  │   │ 40001  │   │ 40001  │
└────────┘   └────────┘   └────────┘
    │              │              │
    └──────────────┼──────────────┘
                   ▼
        ┌─────────────────────┐
        │   共享存储层         │
        │  MySQL (主从)        │
        │  Redis (哨兵/集群)   │
        └─────────────────────┘
```

**架构优势**：
- 无状态服务，可水平扩展
- 共享存储，节点间共享
- 负载均衡，故障自动转移

**CAP 理论**：
```
┌─────────────────────────────────────────┐
│  CAP 定理                                │
│                                         │
│  分布式系统最多同时满足两项：              │
│                                         │
│  C - Consistency (一致性)               │
│  A - Availability (可用性)              │
│  P - Partition Tolerance (分区容错)      │
│                                         │
│  本框架选择：AP (可用性 + 分区容错)        │
│  - 最终一致性                            │
│  - 高可用性                              │
└─────────────────────────────────────────┘
```

---

## 部署方式

### Jar 包部署

**传统部署方式**：
```bash
# 构建项目
mvn clean package -DskipTests

# 启动服务（可执行模块为 agent-app）
java -jar agent-app-*.jar \
  --spring.profiles.active=prod \
  --server.port=40001
```

**JVM 参数优化**：
```bash
java -Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -jar agent-app-*.jar
```

| 参数 | 说明 |
|------|------|
| `-Xms2g` | 初始堆内存 2GB |
| `-Xmx2g` | 最大堆内存 2GB |
| `-XX:+UseG1GC` | 使用 G1 垃圾收集器 |
| `-XX:MaxGCPauseMillis=200` | 最大 GC 停顿 200ms |

### Docker 部署

**容器化优势**：
| 优势 | 说明 |
|------|------|
| **环境一致** | 开发、测试、生产环境一致 |
| **快速部署** | 秒级启动 |
| **资源隔离** | 进程级隔离 |
| **易于扩展** | 快速复制实例 |

#### 基础设施一键启动

项目根目录的 `docker-compose.yml` 提供了完整基础设施的一键启动。在 IDE 终端或 PowerShell 中执行：

```powershell
# 在项目根目录下执行
docker compose up -d
```

将启动以下服务（默认 profile，共 8 个容器，全部默认启动，含链路追踪与可视化组件）：

| 容器 | 镜像 | 端口 | 用途 |
|------|------|------|------|
| yunxi-mysql | mysql:8.0 | 3306 | 主数据库 |
| yunxi-redis | redis:7-alpine | 6379 | 缓存/会话 |
| yunxi-milvus | milvusdb/milvus:v2.3.3 | 19530 | 向量数据库 |
| yunxi-milvus-etcd | etcd:v3.5.5 | 2379 | Milvus 元数据 |
| yunxi-milvus-minio | minio/minio | 9000 | Milvus 存储 |
| yunxi-otel-collector | otel/opentelemetry-collector-contrib:0.97.0 | 4318 | 链路追踪收集 |
| yunxi-jaeger | jaegertracing/all-in-one:1.57 | 16686 | 链路追踪可视化（默认启用） |
| yunxi-attu | zilliz/attu:v2.4 | 8000 | Milvus Web 管理界面（默认启用） |

> Milvus 依赖 etcd 与 minio 先进入 healthy 后才会启动，首次启动约需 30-60 秒。`milvus` 服务的 `command: ["milvus", "run", "standalone"]` 不可省略，否则容器会瞬间 `Exited (1)` 退出（详见 FAQ 安装部署章节）。

OTel Collector 的配置文件位于 `scripts/deploy/otel-collector-config.yaml`，默认将 trace 输出到 Docker 日志。

#### 可选启用 aistio 管控面<a name="aistio"></a>

若需要 **Agent 管控 / 治理可视化 / 会话干预** 能力，可在默认基础设施之外追加 `--profile aistio` 一并拉起 AgentScope-Service 管控面：

```powershell
# 同时拉起 yunxi 自有基础设施 + aistio 管控面（共 13 个容器）
docker compose --profile aistio up -d
```

aistio 以 AgentScope-Java 官方发布的 Docker 镜像提供（见官方文档 https://java.agentscope.io/v2/zh/service/quickstart），启动前需先拉起控制面。镜像坐标由 `AISTIO_REGISTRY` / `AISTIO_VERSION` 环境变量控制（默认仓库 `sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope`，默认版本 `2.0.4`；**镜像版本须与 yunxi `agent-aistio` 客户端协商的 API 版本一致**，否则控制面握手可能失败）。一键拉起：

```powershell
# 拉取官方镜像并启动 aistio 管控面（默认 2.0.4，与 yunxi agent-aistio 客户端构建版本对齐）
.\scripts\setup-aistio.ps1
# 指定版本：  .\scripts\setup-aistio.ps1 -AistioVersion 2.0.4
# 指定镜像源：.\scripts\setup-aistio.ps1 -AistioRegistry my-reg.io/agentscope
```

> **镜像版本选择**：本编排默认拉取 `2.0.4`，与 yunxi `agent-aistio` 集成客户端当前的构建版本（`agentscope.version=2.0.4`）对齐，可避免控制面握手失败。如需使用官方预览版 `2.1.0-BETA1`，须先将 `agent-aistio` 客户端升级到 `2.1.0-BETA1` 后，再 `setup-aistio.ps1 -AistioVersion 2.1.0-BETA1`（详见 [06. 配置指南 - aistio](./06-configuration.md#aistio)）。

> **注意**：官方镜像为预编译产物，无需克隆 AgentScope-Java 源码、无需 Maven / Go / npm 构建。本仓库的 `scripts/setup-aistio.ps1` 已内置生产密钥初始化：默认会用 CSPRNG 生成 `AISTIO_POSTGRES_PASSWORD` / `BUILDER_JWT_SECRET` / `BUILDER_INTERNAL_TOKEN` / `BUILDER_VAULT_MASTER_KEY` / `CONTROL_PLANE_BOOTSTRAP_PASSWORD`，写入仓库根 `.env.aistio`（已被 `.gitignore` 忽略，文件权限仅当前用户可读写）。该文件幂等保留旧值（重生成请用 `-Rotate`，会使 Vault 已加密数据失效），初始管理员密码会打印到控制台。也可改用官方 compose 包的 `init-env.sh` 生成 `.env` 密钥。仅供本地 / 开发可用 `-Dev` 跳过生成、回退内置开发明文默认值。

aistio profile 包含 5 个容器（均在 `aistio` profile 下，默认不启动）：

| 容器 | 构建来源 | 端口 | 用途 |
|------|----------|------|------|
| yunxi-aistio-db | postgres:17 | 5432（可选映射） | aistio 元数据库（库/用户/密码均为 `builder`） |
| yunxi-aistio-control | `as-controlplane`（官方镜像） | 8081（内部） | 控制面 |
| yunxi-aistio-data | `as-dataplane`（官方镜像） | 8082（内部） | 数据面 |
| yunxi-aistio-scheduler | `as-scheduler`（官方镜像） | 8083（内部） | 调度面 |
| yunxi-aistio-gateway | `as-gateway`（官方镜像） | 18080（对外） | 网关，对外发布到主机 18080（避开 Nacos 控制台已占用的 8080） |

- 内部 token 为 `compose-local-internal-token-at-least-32chars`（compose 本地联调用，生产务必替换）。
- `aistio-db` 的 `5432` 映射仅用于本地调试，与宿主机已有 Postgres 冲突时可删除该映射行。
- yunxi 侧接入需在 `application.yml` 配置 `yunxi.aistio.*`（详见 [配置指南 · aistio 管控面](./06-configuration.md#aistio)）。

> 配套的内置 MCP 服务（来自 `yunxi-mcp-servers` 项目，端口 40101 / 40102 / 40103 / 40602 / 40601）不在此 `docker compose` 内。开发联调可用 `scripts/start-stack.ps1` 一键拉起 5 个 MCP 服务 + aistio 控制面（幂等，详情见仓库 README）。
>
> 关闭时同样用配套脚本：`scripts/stop-stack.ps1`（加 `-IncludeYunxi` 连 yunxi 平台一起关）；想把 MySQL / Redis / Milvus 等基础容器也停掉则直接 `docker compose down`。MCP 日志在 `logs/mcp/<服务名>.out.log`，aistio 日志用 `docker compose --profile aistio logs -f`。

确认 aistio 各平面健康：

```powershell
docker compose --profile aistio ps
# 网关就绪后访问 http://localhost:18080
```

aistio 控制台（`http://localhost:18080/login`，亦提供于 `:8081`）首次启动会在 PostgreSQL 自动注入种子用户，本地开发用 **admin / admin** 登录即可（其余种子账号 `bob/bob`、`alice/alice`；用户已存在则不会重复注入）。详见 [仓库 README · 登录 aistio 控制台](../../README.md)。

确认所有服务就绪：

```powershell
docker compose ps          # 查看容器状态
```

关闭服务：

```powershell
docker compose down           # 停止但保留数据
docker compose down -v        # 停止并清除所有数据（彻底重置）
```

#### 应用容器构建

```bash
# 构建镜像（Dockerfile 位于项目根目录，多阶段构建，产物为 agent-app 的 Spring Boot 可执行 jar）
docker build -t yunxi-agent-platform:latest .

# 运行容器
docker run -d \
  --name yunxi-agent \
  -p 40001:40001 \
  -e MYSQL_HOST=mysql \
  yunxi-agent-platform:latest
```

### Kubernetes 部署

**K8s 核心概念**：
| 概念 | 说明 |
|------|------|
| **Pod** | 最小部署单元 |
| **Deployment** | 管理 Pod 的副本 |
| **Service** | 提供网络访问 |
| **ConfigMap** | 配置管理 |
| **Secret** | 敏感信息管理 |

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: yunxi-agent-platform
  namespace: yunxi-agent-platform
spec:
  replicas: 2
  template:
    metadata:
      labels:
        app.kubernetes.io/name: yunxi-agent-platform
    spec:
      containers:
      - name: agent-platform
        image: yunxi/agent-platform:2.0.4
        ports:
        - containerPort: 40001
```

> 以上为最小示例，与仓库 `k8s/deployment.yaml` 保持一致；完整生产清单（滚动更新策略、探针、资源限制、反亲和、Prometheus 注解等）以 `k8s/` 目录为准。

---

## 环境准备

### 系统要求

| 组件 | 最低配置 | 推荐配置 |
|------|----------|----------|
| CPU | 4核 | 8核+ |
| 内存 | 8GB | 16GB+ |
| 磁盘 | 50GB | 100GB+ |

### 软件依赖

```bash
# JDK 17
sudo apt install openjdk-17-jdk

# MySQL 8.0+
sudo apt install mysql-server-8.0

# Redis 6.0+
sudo apt install redis-server
```

---

## 监控与告警

### 可观测性体系

```
┌─────────────────────────────────────────┐
│  监控体系                                │
├─────────────────────────────────────────┤
│  Metrics (指标)                         │
│  - CPU、内存、请求量、错误率              │
│  - Prometheus + Grafana                 │
├─────────────────────────────────────────┤
│  Logging (日志)                         │
│  - 应用日志、访问日志                     │
│  - ELK Stack / Loki                     │
├─────────────────────────────────────────┤
│  Tracing (追踪)                         │
│  - 请求链路追踪                          │
│  - Jaeger / Zipkin                      │
└─────────────────────────────────────────┘
```

### Prometheus 配置

```yaml
scrape_configs:
  - job_name: 'agent-platform'
    static_configs:
      - targets: ['localhost:40001']
    metrics_path: '/actuator/prometheus'
```

### 告警规则

```yaml
groups:
  - name: agent-platform
    rules:
      - alert: HighErrorRate
        expr: rate(agent_request_error_count[5m]) > 0.1
```

---

## 备份与恢复

### 备份策略

| 类型 | 频率 | 保留时间 |
|------|------|----------|
| 全量备份 | 每日 | 7天 |
| 增量备份 | 每小时 | 24小时 |
| 日志备份 | 实时 | 30天 |

### 数据库备份

```bash
# 全量备份
mysqldump -u agent -p yunxi_agent_platform > backup_$(date +%Y%m%d).sql

# 恢复
mysql -u agent -p yunxi_agent_platform < backup_20240101.sql
```

---

**上一页**: [07. 开发指南](./07-development.md)  
**下一页**: [09. API 参考 →](./09-api-reference.md)
