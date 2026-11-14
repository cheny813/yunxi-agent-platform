# 08. 部署指南

生产环境部署与运维指南。

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

> 平台采用 AP 取向（可用性 + 分区容错，最终一致性），适合多副本水平扩展场景。

### 生产集群必设项

平台默认 `yunxi.cluster.mode=standalone`（单机 / 开发），**生产多实例部署必须显式切到 `cluster`**。否则分布式状态、沙箱跨实例守卫、RAG 租户隔离等横切关切仍按单机语义运行，集群下会出现数据分叉与安全缺口。切换为**纯配置驱动，无需改动代码**——在统一配置中心的 `config/cluster.yml`（`config/*.yml` 之一）设置：

```yaml
yunxi:
  cluster:
    mode: cluster            # 生产必设：启用 Redis 分布式运行时
    key-prefix: ${YUNXI_CLUSTER_KEY_PREFIX:yunxi:}   # 多套环境共用一套 Redis 时用此前缀隔离命名空间
```

切换 `cluster` 后，横切关切自动按集群语义装配（详见 [06. 配置指南](./06-configuration.md)）：

| 关切 | standalone 行为 | cluster 行为 |
|------|----------------|--------------|
| 分布式状态（Agent 状态 / 记忆 / 会话） | 进程内存储（重启即失） | 共享 Redis（全局 `StringRedisTemplate`，单连接工厂，不私建客户端） |
| 沙箱执行隔离 | 本地子进程沙箱 | Docker 沙箱（强隔离；另可由 `muse-docker` profile 强制开启） |
| 沙箱跨实例并发守卫 | 进程内信号量（仅单实例有效） | 基于 Redis 的分布式锁（SET NX + Lua 释放），同 key 跨实例互斥 |

> **cluster 形态下分布式锁为 fail-closed**：Redis 不可用时获取锁失败即抛异常、沙箱执行受阻，不会静默降级为进程内信号量（否则多实例并发越权 / last-write-wins 覆盖）。运维须保证 Redis 高可用。standalone 形态无跨实例隔离需求，不受此约束。
| RAG 分块存储 | 按（tenant, collection, userId, chunkId）实体键化 | 同左，且 **tenant 为自然键首位**，存储主键按租户派生，跨租户不可见 |

> 生产集群硬性前置：必须提供**共享 Redis**，连接参数走 `spring.data.redis.*`（Spring Boot 3.x 绑定前缀）。未设 `mode=cluster` 时即便拉起多个实例也仍按单机装配——属配置缺口，运行时不会自动降级为集群。

> RAG 租户隔离依赖请求期 `TenantContext` 绑定（由租户解析器在入口注入）。多租户场景须确保入口已正确解析并绑定租户，否则检索回落单租户语义。

---

## 部署方式

### Jar 包部署

**传统部署方式**：
```bash
# 构建项目
mvn clean package -DskipTests

# 启动服务（可执行模块为 yunxi-app）
java -jar yunxi-app-*.jar \
  --spring.profiles.active=prod \
  --server.port=40001
```

**JVM 参数优化**：
```bash
java -Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -jar yunxi-app-*.jar
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

仓库 `deploy/infra/docker-compose.yml` 提供了平台自有基础设施的一键启动（不含 yunxi-app 本身，应用另用 `mvn spring-boot:run -pl yunxi-app` 或 `java -jar yunxi-app-*.jar` 启动）。在 PowerShell 中执行：

```powershell
# 在仓库 deploy/infra 目录下执行
cd deploy/infra
docker compose up -d
```

将启动以下服务（共 9 个容器，全部默认启动，含链路追踪与可视化组件）：

| 容器 | 镜像 | 端口 | 用途 |
|------|------|------|------|
| yunxi-mysql | mysql:8.0 | 3306 | 主数据库 |
| yunxi-redis | redis:7-alpine | 6379 | 缓存/会话/分布式状态 |
| yunxi-milvus | milvusdb/milvus:v2.3.3 | 19530 | 向量数据库 |
| yunxi-milvus-etcd | etcd:v3.5.5 | 2379 | Milvus 元数据 |
| yunxi-milvus-minio | minio/minio | 9000 | Milvus 存储 |
| yunxi-nacos | nacos/nacos-server:v3.2.4 | 8848 / 8080 / 9848 | MCP 注册中心 + 配置热更新 + AI Registry |
| yunxi-otel-collector | otel/opentelemetry-collector-contrib:0.97.0 | 4317 / 4318 | 链路追踪收集 |
| yunxi-jaeger | jaegertracing/all-in-one:1.57 | 16686 | 链路追踪可视化（默认启用） |
| yunxi-attu | zilliz/attu:v2.4 | 8000 | Milvus Web 管理界面（默认启用） |

> Milvus 依赖 etcd 与 minio 先进入 healthy 后才会启动，首次启动约需 30-60 秒。`milvus` 服务的 `command: ["milvus", "run", "standalone"]` 不可省略，否则容器会瞬间 `Exited (1)` 退出。Nacos 3.x 控制台在 `8080`（已从 `8848` 解耦）、gRPC 客户端端口为 `9848`（`8848 + 1000`）；应用 SDK 实际连接 `nacos:9848`。

> aistio 管控面不在此编排内，如需 Agent 管控 / 治理可视化能力，单独见 [aistio 控制面本地编排](#aistio)（`deploy/aistio/`）。

#### aistio 控制面本地编排<a name="aistio"></a>

若需要 **Agent 管控 / 治理可视化 / 会话干预 / 任务接活** 能力，单独拉起 AgentScope-Service 控制面。编排脚本与说明位于仓库 `deploy/aistio/`（`docker-compose.yml` + `01-create-schemas.sql` + `.env.example` + `README.md`），不依赖 yunxi 自有基础设施（MySQL / Redis / Milvus 等），可独立运行。

aistio 以 AgentScope-Java 官方 Docker 镜像提供（见官方文档 https://java.agentscope.io/v2/zh/service/quickstart）。镜像默认 `2.1.0-BETA1`（由 `AISTIO_VERSION` / `AISTIO_REGISTRY` 环境变量覆盖），须与 `yunxi-fabric` 中 aistio 客户端协商的 API 版本一致，否则控制面握手可能失败。

```powershell
# 在仓库 deploy/aistio 目录下执行
cd deploy/aistio
docker compose up -d        # 拉起控制面 / 数据面 / 调度面 / 网关 / 元数据库共 5 容器
```


> 所有敏感值用 `${VAR:-dev默认值}` 兜底，零配置即以开发默认值启动（`BUILDER_INTERNAL_TOKEN=compose-local-internal-token-at-least-32chars` 等）；生产环境在 `deploy/aistio/` 放置同级 `.env`（已被 `.gitignore` 忽略）覆盖全部密钥，切勿向仓库提交明文。控制面首次启动会在 PostgreSQL 注入种子用户，本地开发用 **admin / admin** 登录。




aistio 编排包含 5 个容器（均默认启动）：

| 容器 | 镜像 | 主机端口 | 用途 |
|------|------|----------|------|
| aistio-db | postgres:17 | 不映射（仅容器内网） | 元数据库（库/用户/密码均为 `builder`） |
| aistio-control | `as-controlplane`（官方镜像） | 18081 | 控制面（yunxi `yunxi.aistio.control-plane-url=http://localhost:18081`） |
| aistio-data | `as-dataplane`（官方镜像） | 18082 | 数据面 |
| aistio-scheduler | `as-scheduler`（官方镜像） | 18083 | 调度面 |
| aistio-gateway | `as-gateway`（官方镜像） | 18090 | 网关，对外发布到主机 18090（避开常见 8080 占用） |

- 主机端口统一落在空闲高位段，避开本地已占用的 5432 / 8080 / 8081 等；如需本地调试数据库，可在 `aistio-db` 的 `ports` 中自行加 `"5432:5432"`（与宿主机 Postgres 冲突时勿加）。
- yunxi 侧接入需在 `config/aistio.yml`（统一配置中心的 `config/*.yml` 之一）配置 `yunxi.aistio.*`（详见 [配置指南 · aistio 管控面](./06-configuration.md#aistio)）。

> 配套的 MCP 服务（来自 `yunxi-mcp-servers` 项目）不在此编排内：平台 `config/mcp.yml` 当前仅声明 `nutrition-mcp`（40602）与 `text2sql-mcp`（40110），且默认关闭。开发联调时按需拉起对应 Java 进程并置 `enabled: true`（详情见仓库 README）。

确认 aistio 各平面健康：

```powershell
docker compose ps          # 查看容器状态
docker compose logs -f     # 跟踪日志
```

aistio 控制台（`http://localhost:18090/login`，亦提供于 `http://localhost:18081`）首次启动会在 PostgreSQL 自动注入种子用户，本地开发用 **admin / admin** 登录即可（其余种子账号 `bob/bob`、`alice/alice`；用户已存在则不会重复注入）。详见 [仓库 README · 接入 aistio 控制面](../../README.md)。

关闭服务：

```powershell
docker compose down           # 停止但保留数据
docker compose down -v        # 停止并清除所有数据（彻底重置，下次 up 重新建 schema）
```

#### 应用容器构建

```bash
# 构建镜像（多阶段 Dockerfile 位于 yunxi-app/，产物为 yunxi-app 的 Spring Boot 可执行 jar）
docker build -f yunxi-app/Dockerfile -t yunxi-agent-platform:latest .

# 运行容器
docker run -d \
  --name yunxi-agent \
  -p 40001:40001 \
  -e MYSQL_HOST=mysql \
  yunxi-agent-platform:latest
```

### Kubernetes 部署

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
        image: yunxi/agent-platform:2.1.0
        ports:
        - containerPort: 40001
```

> 以上为最小示例；完整生产清单（滚动更新策略、探针、资源限制、反亲和、Prometheus 注解等）请以 [06. 配置指南](./06-configuration.md) 与 `deploy/infra/docker-compose.yml` 为基线自行编排。当前仓库**未随附** `k8s/` 目录的 Kubernetes 清单与 `helm/` Chart（部署目录仅有 `deploy/`，含 `infra/` 与 `aistio/` 的 docker-compose 基线）。

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
