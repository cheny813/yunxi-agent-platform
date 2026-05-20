# Agent Rule Engine 规则引擎模块

轻量级规则引擎，支持动态配置和执行 Agent 约束规则。

---

## 🚀 快速启动

### 启动服务

```bash
# 方式 1: 从项目根目录（推荐）
启动规则引擎.bat

# 方式 2: 从模块目录
cd agent-rule-engine/scripts
start.bat        # Windows
./start.sh       # Linux/Mac
```

### 验证启动

```bash
# 健康检查
curl http://localhost:40002/actuator/health

# 查询规则列表
curl http://localhost:40002/api/rules
```

---

## 📦 模块信息

| 项目 | 说明 |
|------|------|
| **端口** | 40002 |
| **数据库** | MySQL (192.168.10.153:3306/nutrition_zhaoxian) |
| **API 地址** | http://localhost:40002/api/rules |

---

## 🎯 功能特性

### 1. 规则类型

| 类型 | 说明 | 执行时机 |
|------|------|---------|
| **PRE** | 前置规则 | Agent 执行前检查 |
| **RUNTIME** | 运行时规则 | Agent 执行中约束 |
| **POST** | 后置规则 | Agent 执行后验证 |

### 2. 内置规则

| 规则名称 | 功能 |
|---------|------|
| `PermissionRule` | 权限检查（验证用户权限） |
| `AuditLogRule` | 审计日志（记录操作历史） |

---

## 📝 使用示例

### 添加规则

```bash
curl -X POST http://localhost:40002/api/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "business-hours-check",
    "type": "PRE",
    "priority": 100,
    "enabled": true,
    "conditionExpression": "#facts.get('hour') >= 9 && #facts.get('hour') < 18",
    "actionExpression": "#facts.put('allowed', true)",
    "description": "工作时间检查规则"
  }'
```

### 查询规则

```bash
# 查询所有规则
curl http://localhost:40002/api/rules

# 查询单个规则
curl http://localhost:40002/api/rules/{id}
```

### 执行规则

```bash
# 执行前置规则检查
curl -X POST http://localhost:40002/api/rules/execute/pre \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user001",
    "taskId": "task123"
  }'
```

---

## ⚙️ 配置说明

### 数据库配置

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://192.168.10.153:3306/nutrition_zhaoxian
    username: root
    password: root
```

### 规则引擎配置

```yaml
agent:
  rule:
    engine:
      enabled: true
      default-timeout: 300
      max-instances: 100
      audit-log-enabled: true
```

---

## 📚 项目结构

```
agent-rule-engine/
├── src/main/java/com/yunxi/agent/rule/
│   ├── core/                   # 核心引擎
│   ├── model/                  # 数据模型
│   ├── repository/             # 数据访问
│   ├── builtin/                # 内置规则
│   └── controller/             # REST API
├── src/main/resources/
│   ├── db/migration/           # 数据库脚本
│   └── application.yml         # 配置文件
└── scripts/                    # 启动脚本
    ├── start.bat
    └── start.sh
```

---

## 🔧 集成到 Agent Core

### 1. 添加依赖

```xml
<!-- agent-core/pom.xml -->
<dependency>
    <groupId>io.yunxi</groupId>
    <artifactId>agent-rule-engine</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 使用规则引擎

```java
@Autowired
private RuleEngine ruleEngine;

public Object executeAgent(AgentTask task) {
    RuleContext context = new RuleContext();
    context.setTask(task);
    
    // 前置规则检查
    RuleResult result = ruleEngine.checkPreRules(context);
    if (!result.isPassed()) {
        throw new RuleViolationException(result.getErrorMessage());
    }
    
    // 执行任务
    return doExecute(task);
}
```

---

## 📊 数据库表

### agent_rule（规则定义）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| name | VARCHAR(100) | 规则名称 |
| type | VARCHAR(20) | 规则类型（PRE/RUNTIME/POST） |
| priority | INT | 优先级（越大越先执行） |
| enabled | BOOLEAN | 是否启用 |
| condition_expression | TEXT | 条件表达式（SpEL） |
| action_expression | TEXT | 动作表达式（SpEL） |

### agent_rule_execution_log（执行日志）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| rule_id | BIGINT | 规则ID |
| execution_time | DATETIME | 执行时间 |
| passed | BOOLEAN | 是否通过 |
| error_message | TEXT | 错误信息 |

---

## 🐳 Docker 部署

```bash
# 构建镜像
docker build -t yunxi-agent-rule-engine:1.0.0 .

# 运行容器
docker run -d \
  --name rule-engine \
  -p 40002:40002 \
  -e MYSQL_HOST=192.168.10.153 \
  yunxi-agent-rule-engine:1.0.0
```

---

## 📖 更多信息

- **设计方案**: `docs/rule-engine-design.md`
- **多模块迁移**: `MODULE_MIGRATION_SUMMARY.md`
- **项目主文档**: `README.md` (项目根目录)

---

## 🎯 常见问题

**Q: 端口被占用怎么办？**

修改 `application.yml` 中的 `server.port`。

**Q: 数据库连接失败？**

检查 MySQL 服务状态和连接配置。

**Q: 如何添加自定义规则？**

实现 `RuleDefinition` 接口，或通过 REST API 动态添加。
