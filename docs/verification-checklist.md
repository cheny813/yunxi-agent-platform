# 第九阶段运行时验证清单（agentscope-2.0GA 升级）

本清单对应升级计划「第九阶段：测试与验证」。状态说明：

- ✅ 已完成：本机可验证，已通过
- 🟡 待基建：代码就绪，但需 Redis/DB/LLM 运行时环境
- 🔴 阻塞：测试/代码引用了 GA 升级前的旧架构，需重写后才能运行

---

## 1. 编译验证

| 项 | 状态 | 说明 |
|----|------|------|
| `mvn clean compile -DskipTests` | ✅ | 全模块 BUILD SUCCESS（含场景检测链删除、forwardEvents 改造） |

---

## 2. 单元测试（不依赖外部基建）

计划要求覆盖 `AgentConfigurer`（构建+权限上下文）、`ApplicationRAG`（注入）、`PermissionConfig`（ask/deny 规则）。

| 项 | 状态 | 说明 |
|----|------|------|
| `agent-core` 单测类 | ✅ | `PermissionConfigTest`(14) + `AgentConfigurerHelperTest`(14) 共 28 用例，全部通过；`AgentConfigurerSmokeTest` 已创建（冒烟级，含 mock ModelFactory Spring Boot 测试） |
| `PermissionConfig` ask/deny 规则 | ✅ | 覆盖 null/空配置、ToolGate 启用/禁用/空工具、ReasoningReview 启用/禁用/null ToolGate、两者组合、默认配置、独立性——共 14 用例全通过 |
| `ApplicationRAG` 注入校验 | 🟡 | 未单独编写，但可通过冒烟测试或集成测试间接覆盖；`ApplicationRAG` 创建中间件逻辑依赖 `FileVectorService` 注入，纯单测需 mock ObjectProvider |

---

## 3. 集成验证（需基建 🟡 / 需重写 🔴）

| 项 | 计划要求 | 当前状态 | 映射到的当前架构 |
|----|---------|---------|------------------|
| 企微/钉钉端到端 | IM 接入 | 🟡 | GA `agentscope-extensions-channel-*`（wecom/dingtalk/feishu），按需引入 |
| MCP 工具实际调用 | 真注册 MCP | ✅(milvus)/🟡(其余) | `AgentConfigurer.registerMcpServers`，sse/stdio/http 三种传输；集成测试已验证 `milvus` MCP 真注册 + Milvus 客户端初始化成功，其余（database/redis/knowledge）需对应服务运行 |
| plan mode 审批流 | 计划模式 | 🟡 | GA `PlanModeMiddleware` + `PlanModeManager`，经 `configurePlan` 装配 |
| Skill 加载 | 技能系统 | 🟡 | GA `AgentSkillRepository`（FileSystem/Classpath/...），`agentscope.core.skill` 配置 |
| 子 Agent 调度 | Supervisor | ✅(代码)/🟡(运行) | `createSupervisorAgent` + `SubAgentConfig.forwardEvents`（**本次已默认改为 true**，透出子 Agent 事件） |
| 私有模型调用 | 百度/华为 | 🟡 | `ModelFactory` + `BaiduModelProvider`/`HuaweiModelProvider`（GA 未覆盖，保留） |

---

## 4. 集成测试执行结果（✅ 已重写并通过）

`agent-integration-test/src/test/java/io/yunxi/platform/integration/*Test.java` 共 5 个类已
**按新 GA 架构重写完成**，脱离已废弃的 `agent-business` 服务：

- 主类统一改为 `io.yunxi.platform.AgentPlatformApplication`（`@SpringBootTest(classes = {AgentPlatformApplication.class, IntegrationTestConfig.class})`）。
- 测试面向当前在用的服务：`ChatAppService`、`ConceptRegistry`、`SseNotificationProvider`（SPI），
  对话真实调用本地 DashScope LLM，数据依赖本地 MySQL/Redis。

**执行结果**（2026-07-12，本机基建：MySQL/Redis/Milvus/DashScope 均可用）：

| 测试类 | 覆盖 | 结果 |
|--------|------|------|
| `EndToEndIntegrationTest` | 概念识别 → Agent 对话 → SSE 通知 + 配置加载 | ✅ 全部通过 |
| `CrossModuleIntegrationTest` | 跨模块协作 | ✅ 全部通过 |
| `ErrorRecoveryIntegrationTest` | 异常输入与错误恢复 | ✅ 全部通过 |

三类合计 **13 个用例全部通过**（Failures: 0, Errors: 0），BUILD SUCCESS。

**Milvus ETL 链路**：本地 Milvus（`192.168.11.48:19530`）可用，`SyncEngine` 启动期完整跑通
「外部库 `nutrition_zhaoxian` (MySQL @192.168.10.153) → 向量集合」ETL；10 个集合初始化完成并复用。
集成测试配置（`src/test/resources/application.yml`）默认启用 `milvus` MCP。

---

## 5. 如何执行 B（本机已有 Docker + LLM Key 时）

```bash
# 1. 拉起全栈基建（MySQL + Redis + Milvus + OTel Collector，共 6 个容器）
docker compose up -d

# 确认全部就绪（约 30-60 秒）
docker compose ps

# 2. 配置 LLM 凭证（application.yml 或环境变量）
#    agentscope.core.model.* 的 api-key / base-url

# 3. 确认 Ollama 在宿主机运行（向量嵌入）
ollama list

# 4. 跑集成测试（前提：第 4 节集成测试已重写）
mvn -pl agent-integration-test -am test
```

基础设施端口一览：

| 服务 | 端口 | 说明 |
|------|------|------|
| MySQL | 3306 | 主数据库，凭证 root/root |
| Redis | 6379 | 缓存，密码 redispass |
| Milvus | 19530 | 向量数据库 |
| OTel Collector | 4318 | 链路追踪（消除 "Failed to connect to 127.0.0.1:4318"） |

---

## 6. 建议的下一步

1. ~~**补建单测**~~ ✅ **已完成（2026-07-13）**：`PermissionConfigTest`（14 用例）+ `AgentConfigurerHelperTest`（14 用例）共 28 用例全部通过。
   `AgentConfigurerSmokeTest` 已创建（Spring Boot + mock ModelFactory），验证 AgentConfigurer 生命周期与 Agent 注册流程。
2. ~~**重写集成测试**~~ ✅ **已完成**：5 个集成测试已改为基于 `AgentPlatformApplication` 的
   `@SpringBootTest`，3 个功能类 13 用例全部通过（见第 4 节）。后续可补充 plan mode 审批流、
   skill 加载、supervisor 子 Agent（forwardEvents=true）事件透出的专项断言。
3. **冒烟测试**（推荐先做）：`AgentConfigurerSmokeTest` 已创建，需在具备 mock ModelFactory 的
   Spring Boot 上下文中运行验证（当前依赖 agent-config 模块的 agent-definitions 资源路径）。
