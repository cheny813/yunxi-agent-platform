# yunxi-mcp-servers 路径 B 改造完成报告

## 改造原则

彻底消除自建协议层，完全复用底层框架（官方 MCP SDK 0.17.0），不自建底层已有功能，删除所有废弃代码。

---

## 一、yunxi-mcp-servers 侧（mcp-common 模块）

### 1.1 新增依赖

**`mcp-common/pom.xml`** 新增：
```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
    <version>0.17.0</version>
</dependency>
```

### 1.2 简化 ToolHandler 接口

**文件**: `mcp-common/.../handler/ToolHandler.java`

旧接口（依赖自建 ToolDefinition/ToolResult）：

```java
public interface ToolHandler {
    ToolDefinition getDefinition();
    ToolResult execute(Map<String, Object> arguments);
    default String getName() { return getDefinition().getName(); }
}
```

新接口（无自建类型依赖）：

```java
public interface ToolHandler {
    String getName();
    String getDescription();
    Map<String, Object> getInputSchema();
    String execute(Map<String, Object> arguments);
}
```

### 1.3 新增 McpServerFactory

**文件**: `mcp-common/.../config/McpServerFactory.java`

核心功能：
- 使用官方 MCP SDK 的 `McpSyncServer` + `HttpServletSseServerTransportProvider`
- 自动发现所有 `ToolHandler` Spring Bean，注册为 MCP 工具
- SSE 端点：`/mcp/sse` + `/mcp/message`
- 通过 `serverInfo` 声明服务器名称和版本（从 `mcp.server.name/version` 配置读取）
- 自动处理异常并设置 `isError` 标志

各服务器模块只需：
1. 注入 `McpServerFactory` 依赖
2. 将 ToolHandler 标注 `@Component`
3. 配置 `mcp.server.name` 和 `server.port`

### 1.4 删除的自建协议层（8 个文件）

| 文件 | 说明 | 替代方案 |
|------|------|----------|
| `server/AbstractMcpEndpoint.java` | 自建 JSON-RPC 分发器 | SDK 的 McpSyncServer |
| `server/McpSseEndpoint.java` | 自建 SSE 端点 | SDK 的 HttpServletSseServerTransportProvider |
| `server/McpHttpEndpoint.java` | 自建 HTTP 端点 | SDK 的 McpSyncServer |
| `server/AbstractMcpServer.java` | 自建 Stdio Server | SDK 的 McpServer.sync() |
| `controller/AbstractMcpController.java` | 自建 Spring MVC 控制器 | McpServerFactory + SDK |
| `protocol/McpRequest.java` | 自建 JSON-RPC 请求模型 | SDK 内部处理 |
| `protocol/McpResponse.java` | 自建 JSON-RPC 响应模型 | SDK 内部处理 |
| `protocol/McpError.java` | 自建 JSON-RPC 错误模型 | SDK 内部处理 |

**删除代码量**：约 1200 行自建协议代码。

### 1.5 改造的文件

| 文件 | 改动内容 |
|------|----------|
| `config/McpSecurityAutoConfig.java` | 更新注释，适配 SDK 端点路径 `/mcp/sse`、`/mcp/message` |
| `controller/McpGlobalExceptionHandler.java` | 精简：移除协议级错误处理（由 SDK 负责），保留 Spring MVC 兜底异常 |
| `constants/McpErrorCode.java` | 保留（仍被异常处理器使用） |
| `model/ToolDefinition.java` | 保留但已废弃（新 ToolHandler 不使用，但旧模块可能引用） |
| `model/ToolResult.java` | 保留但已废弃（同上） |
| `model/ToolParameter.java` | 保留（辅助 JSON Schema 构建，非协议类型） |
| `model/McpToolSchema.java` | 保留（辅助 JSON Schema 构建，非协议类型） |

---

## 二、yunxi-agent-platform 侧

### 2.1 删除的自建 MCP 客户端（7 个文件）

| 文件 | 说明 |
|------|------|
| `mcp/McpClient.java` | MCP 客户端接口（无任何实现类） |
| `mcp/McpClientConfig.java` | MCP 客户端配置 + McpDatabaseClient Bean 工厂 |
| `mcp/McpClientService.java` | RestTemplate 构建 JSON-RPC 2.0 请求调用 MCP 服务器 |
| `sync/McpQueryService.java` | JSON-RPC 2.0 调用 mcp-database-server 的 query_db 工具 |
| `controller/McpToolController.java` | 已废弃的管理端点（所有方法返回 "V2.0 已移除"） |
| `shared/exception/McpClientException.java` | 无任何引用 |
| `shared/util/mcp/McpDatabaseClient.java` | 自建 MCP 数据库客户端（RestTemplate + JSON-RPC） |

**删除代码量**：约 900 行自建客户端代码。

### 2.2 新增替代服务

**文件**: `sync/ExternalDbQueryService.java`

核心功能：
- 通过 HikariCP 连接池直接 JDBC 连接目标数据库
- 替代 McpQueryService 的 MCP 代理模式
- 按 JDBC URL 缓存 DataSource，避免重复创建连接池
- 支持 `query()`、`describeTable()`、`listTables()` 操作

### 2.3 更新的引用文件

| 文件 | 改动内容 |
|------|----------|
| `BaseSyncService.java` | McpQueryService → ExternalDbQueryService；`callMcpDatabase()` → `queryDatabase()` |
| `SyncEngine.java` | `mcpDbHost/mcpDbPort` → `dbJdbcUrl/dbUsername/dbPassword`；直接 JDBC 调用 |
| `SchoolDishSyncRunner.java` | 同上，4 处 MCP 调用全部替换 |
| `MultiDatabaseQueryService.java` | 完整重写：McpDatabaseClient → ExternalDbQueryService + MultiDatabaseConfig |
| `MultiDatabaseConfig.java` | DatabaseInfo 新增 `jdbcUrl`、`username`、`password` 字段 |

### 2.4 配置变更

| 旧配置 | 新配置 |
|--------|--------|
| `static-sync.mcp-database.host` | `static-sync.database.jdbc-url` |
| `static-sync.mcp-database.port` | `static-sync.database.username` / `password` |
| `dish-sync.mcp-database.host` | `dish-sync.database.jdbc-url` |
| `dish-sync.mcp-database.port` | `dish-sync.database.username` / `password` |
| `mcp.database.host/port` | 通过 `MultiDatabaseConfig.DatabaseInfo.jdbcUrl` 配置 |

---

## 三、待后续完成的工作

### 3.1 各 MCP 服务器模块适配（20 个模块）

每个模块需要：

1. **删除** `McpController extends AbstractMcpController` 类
2. **更新** ToolHandler 实现为新接口：
   - `getDefinition()` → 拆分为 `getName()`、`getDescription()`、`getInputSchema()`
   - `execute()` 返回 `ToolResult` → 返回 `String`（错误时抛异常）
3. **创建** `McpServerConfig` 配置类或直接使用 McpServerFactory
4. **配置** `application.yml` 添加 `mcp.server.name`

参考 mcp-database 模块的改造示例：

```java
// 旧：McpController extends AbstractMcpController
// 新：由 McpServerFactory 自动处理，无需自定义 Controller

// 旧 ToolHandler 实现：
@Component
public class QueryTool implements ToolHandler {
    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name("query_db")
            .description("Execute SQL query")
            .inputSchema(Map.of(...))
            .build();
    }
    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        return ToolResult.text(result);
    }
}

// 新 ToolHandler 实现：
@Component
public class QueryTool implements ToolHandler {
    @Override
    public String getName() { return "query_db"; }
    @Override
    public String getDescription() { return "Execute SQL query"; }
    @Override
    public Map<String, Object> getInputSchema() { return Map.of(...); }
    @Override
    public String execute(Map<String, Object> arguments) { return result; }
}
```

### 3.2 配置文件迁移

> 说明：yunxi-agent-platform 实际**未采用** `mcp-servers.json`，而是用 `agentscope.core.mcp-servers`（YAML，见 `mcp-core.yml`），由 `AgentConfigurer.registerMcpServers()` 读取并以框架原生 `McpClientBuilder` 真注册。下方仅为 AgentScope 原生 `mcp-servers.json` 格式示意。

需要创建 `mcp-servers.json` 标准配置（AgentScope 原生格式示意，本平台未直接使用）：

```json
{
  "mcpServers": {
    "yunxi-database": { "transport": "sse", "url": "http://localhost:40101/mcp/sse" },
    "yunxi-redis": { "transport": "sse", "url": "http://localhost:40102/mcp/sse" },
    "yunxi-milvus": { "transport": "sse", "url": "http://localhost:40103/mcp/sse" }
  }
}
```

### 3.3 编译验证

```bash
cd d:\work\code\yunxi-mcp-servers
mvn clean compile -pl mcp-common
```

---

## 四、统计数据

| 指标 | 数量 |
|------|------|
| 删除的自建协议文件 | 15 个（mcp-common 8 + agent-platform 7） |
| 删除的代码行数 | ~2100 行 |
| 新增文件 | 3 个（ToolHandler v2、McpServerFactory、ExternalDbQueryService） |
| 修改的文件 | 10 个 |
| 待适配的服务器模块 | 20 个 |
| 新增依赖 | `io.modelcontextprotocol.sdk:mcp:0.17.0` |

## 五、验证要点

1. **编译通过**：mcp-common 模块能正常编译（依赖官方 MCP SDK）
2. **协议兼容**：McpServerFactory 使用官方 SDK 的 SSE 传输，与 AgentScope McpClientBuilder 天然兼容
3. **安全认证**：McpAuthFilter 拦截路径 `/mcp/*` 覆盖 SDK 的 `/mcp/sse` 和 `/mcp/message`
4. **数据访问**：SyncEngine 和 SchoolDishSyncRunner 从 MCP 代理切换到直接 JDBC，需确认数据库可直连

## 六、Agent 侧补充改造（2026-07-11）

路径 B 服务端改造完成后，agent-platform 侧仍有两处与“完全复用框架、无自建”相悖的残留，本次一并处理：

1. **残留自建 MCP HTTP 客户端（`DatabaseToolkit` 体系）已删除**：`DatabaseToolkit` / `SchemaInspector` / `RelationshipMapper` / `DataExplorer` / `SqlAnalyzer` / `SqlValidator` 内嵌手工拼 JSON-RPC 2.0 经 `RestTemplate` `POST http://{host}:{port}/mcp` 的 `callMcpTool`（不在前述被删的 7 个类之中），且功能与 database MCP 服务器重叠、从未被调用（死代码）。连同 `DatabaseToolkitConfig` Bean 一并删除，agent-platform 侧不再有任何自建 MCP 客户端。
2. **Agent 侧 MCP 真注册已落地（P1-5）**：原 `AgentscopeAutoConfiguration.mcpServerBeans()` 仅把 `agentscope.core.mcp-servers` 封装成死 Map Bean，从未注册工具；现由 `AgentConfigurer.registerMcpServers()` 在构建每个 Agent 的 `Toolkit` 时，按 `tools.mcpServers` / `toolsGroup.mcpServersToolsGroup` 读取配置，用框架原生 `McpClientBuilder`（SSE/STDIO/HTTP）实例化 `McpClientWrapper`，以**服务器名建组**后 `toolkit.registration().mcpClient(wrapper).group(name).apply()` 注册进 `Toolkit`，与原 `applyToolGroupActivation` 分组激活衔接。单个服务器注册失败仅告警，不影响其余服务器与 Agent 启动。
