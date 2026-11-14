# aistio 控制面本地编排

yunxi 平台接入 AgentScope-Service aistio 控制面（管控 / 治理 / 会话干预）的本地与开发编排，共 5 个容器：元数据库、控制面、数据面、调度面、网关。镜像默认 `2.1.0-BETA1`。

> 该编排是 yunxi 平台自身的运行脚本，不依赖 yunxi 自有基础设施（MySQL / Redis / Milvus 等），可独立拉起用于联调与集成测试。

## 目录

| 文件 | 说明 |
|---|---|
| `docker-compose.yml` | 5 容器编排；敏感值用 `${VAR:-dev默认值}` 兜底，零配置即可启动 |
| `01-create-schemas.sql` | 首次启动为 `builder` 库建好各平面所需 schema（幂等，挂载进 postgres init 目录） |
| `.env.example` | 可外部覆盖的变量样例，复制为 `.env` 后修改 |

## 启动 / 停止

```powershell
# 在项目根目录下
cd deploy/aistio

# 零配置启动（使用开发默认值）
docker compose up -d

# 生产：先放置同级 .env 覆盖密钥，再启动
cp .env.example .env   # 编辑 .env 填入强随机密钥
docker compose up -d
```

```powershell
docker compose ps                 # 查看容器状态
docker compose logs -f           # 跟踪全部日志
docker compose down              # 停止但保留数据
docker compose down -v           # 停止并清除数据（彻底重置，下次 up 重新建 schema）
```

## 端口（主机映射）

| 容器 | 内部端口 | 主机端口 | 用途 |
|---|---|---|---|
| aistio-control | 8080 | 18081 | 控制面 API（yunxi 接入 `yunxi.aistio.control-plane-url=http://localhost:18081`） |
| aistio-data | 8082 | 18082 | 数据面 |
| aistio-scheduler | 8083 | 18083 | 调度面 |
| aistio-gateway | 8080 | 18090 | 网关，对外发布到主机 18090 |
| aistio-db | 5432 | 不映射 | 元数据库（仅容器内网访问） |

> 主机端口统一落在空闲高位段，避开本地已占用的 5432 / 8080 / 8081 等；如需本地调试数据库，可在 `aistio-db` 的 `ports` 中自行加 `"5432:5432"`（与宿主机 Postgres 冲突时勿加）。

## 登录控制台

控制面首次启动会在 PostgreSQL 注入种子用户，本地开发用 **admin / admin** 登录：

- 网关：`http://localhost:18090/login`
- 控制面：`http://localhost:18081`

其余种子账号 `bob/bob`、`alice/alice`（用户已存在则不会重复注入）。

## 与 yunxi 平台接入

yunxi 侧在 `application.yml` 配置（详见 `docs/guide/06-configuration.md` 的 aistio 段）：

```yaml
yunxi:
  aistio:
    enabled: true
    control-plane-url: http://localhost:18081
    base-url: http://localhost:40001
    internal-token: compose-local-internal-token-at-least-32chars   # 须与本编排 BUILDER_INTERNAL_TOKEN 一致
```

控制台登录拿 JWT：`POST http://localhost:18081/api/auth/login` 带 `{"username":"admin","password":"admin"}`；内部调用携带 `X-Builder-Internal-Token: compose-local-internal-token-at-least-32chars`。

## 集成测试

`yunxi-integration-test` 中的 aistio 真实控制面测试（如 `AistioRealControlPlaneRegistrationTest`）前置条件即本编排运行于 `http://localhost:18081`。测试本身不自动拉起容器，需先手动 `docker compose up -d`。

## 生产注意

- 必须覆盖 `.env` 中的 `BUILDER_JWT_SECRET` / `BUILDER_INTERNAL_TOKEN` / `BUILDER_VAULT_MASTER_KEY` / `CONTROL_PLANE_BOOTSTRAP_PASSWORD` / `AISTIO_POSTGRES_PASSWORD`，不要沿用开发明文默认值。
- 不要将 `aistio-db` 的 5432 端口映射到公网。
- 镜像版本须与 yunxi `yunxi-fabric` 中 aistio 客户端协商的 API 版本一致，否则控制面握手可能失败。
