# scripts/ 目录说明

本目录集中存放 yunxi-agent-platform 的**运维、调试、验证类脚本**，与 SDK（`sdk-js/`）和前端演示（`agent-config/.../static/`）解耦，不属于框架发布产物。

按用途分类：

| 子目录 | 用途 | 文件 |
|--------|------|------|
| `perf/`   | 性能压测、GC / 线程池 / 连接池监控 | `analyze-gc.jl`、`db-pool-monitor.jl`、`api-performance-monitor.java`、`performance-test.java`、`performance-monitor.groovy`、`thread-pool-config.yml`、`db-stress-test.java`、`memory-alert.bat` |
| `deploy/` | 部署与可观测性配置 | `deployment-guide.md`、`otel-collector-config.yaml` |
| `verify/` | 结构 / 构建 / 优化验证 | `build-verification.sh`、`final-verification.py`、`optimization-verification.sh` |
| `tools/`  | 一次性迁移 / 清理工具 | `migrate-agent-node.ps1`、`cleanup-directory-structure.ps1` |

## 说明

- 这些脚本大多为**本地调试 / 运维辅助**用途，非运行时依赖；移动或重命名时请同步更新引用方（见下）。
- 交叉引用清单（重命名文件后需同步修改）：
  - `docker-compose.yml` -> `deploy/otel-collector-config.yaml`
  - `docs/guide/15-observability.md` / `docs/guide/08-deployment.md` -> `deploy/otel-collector-config.yaml`
  - `verify/optimization-verification.sh` / `verify/final-verification.py` -> 各脚本的新路径
  - `deploy/deployment-guide.md` 启动命令指向仓库根目录的 `start.ps1` / `start.bat`

> 注意：`cleanup-directory-structure.ps1` 是早期的一次性目录整理规划脚本，其建议的子目录（`scripts/startup`、`scripts/shutdown` 等）与当前布局不一致，仅作历史参考，请勿对其输出照单全收。
