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
│  │  ┌─────────┐ ┌─────────┐     │   │
│  │  │  Core   │ │ Gateway │     │   │
│  │  └─────────┘ └─────────┘     │   │
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

# 启动服务
java -jar agent-core-*.jar \
  --spring.profiles.active=prod \
  --server.port=40001
```

**JVM 参数优化**：
```bash
java -Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -jar agent-core-*.jar
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

```bash
# 构建镜像
docker build -t yunxi-agent-core:latest ./agent-core

# 运行容器
docker run -d \
  --name agent-core \
  -p 40001:40001 \
  -e MYSQL_HOST=mysql \
  yunxi-agent-core:latest
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
  name: agent-core
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: agent-core
        image: yunxi-agent-core:latest
        ports:
        - containerPort: 40001
```

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
mysqldump -u agent -p agent_platform > backup_$(date +%Y%m%d).sql

# 恢复
mysql -u agent -p agent_platform < backup_20240101.sql
```

---

**上一页**: [07. 开发指南](./07-development.md)  
**下一页**: [09. API 参考 →](./09-api-reference.md)
