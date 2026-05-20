# agent-business 业务模块

业务 Agent 实现层，承载具体业务领域的 Agent 逻辑和 SPI 扩展。

## 模块定位

**业务实现层**，依赖 agent-core，通过 SPI 机制扩展框架能力。

- **业务域**：nutrition(营养)、devops(运维)、a2apipeline(A2A流水线)、reportquery(报表查询)
- **扩展方式**：实现 agent-core 的 SPI 接口
- **规则集成**：通过 agent-rule-engine 的 SPI 注册业务规则

## 业务域说明

### 1. 营养域 (nutrition)

营养相关 Agent 实现：

- **食谱分析**：营养成分计算、膳食评估
- **配餐优化**：基于营养标准的智能配餐
- **食材管理**：食材库维护、营养数据查询

### 2. 运维域 (devops)

运维相关 Agent 实现：

- **部署管理**：应用部署、回滚、扩缩容
- **监控告警**：系统监控、告警处理
- **日志分析**：日志查询、异常分析

### 3. A2A流水线域 (a2apipeline)

Agent-to-Agent 流水线：

- **流程编排**：多 Agent 协作流程定义
- **任务调度**：流水线任务调度执行
- **结果聚合**：多 Agent 结果汇总

### 4. 报表查询域 (reportquery)

报表相关 Agent：

- **报表生成**：基于模板生成各类报表
- **数据查询**：自然语言查询报表数据
- **图表展示**：数据可视化

## SPI 扩展点

### 1. DomainContributor

贡献业务域定义：

```java
@Component
public class NutritionDomainContributor implements DomainContributor {
    @Override
    public DomainDefinition contribute() {
        return DomainDefinition.builder()
            .code("nutrition")
            .name("营养域")
            .description("营养相关业务")
            .build();
    }
}
```

### 2. SceneContributor

贡献场景定义：

```java
@Component
public class RecipeSceneContributor implements SceneContributor {
    @Override
    public SceneDefinition contribute() {
        return SceneDefinition.builder()
            .code("recipe-analysis")
            .name("食谱分析")
            .domain("nutrition")
            .description("分析食谱营养成分")
            .build();
    }
}
```

### 3. ContextEnricher

丰富对话上下文：

```java
@Component
public class NutritionContextEnricher implements ContextEnricher {
    @Override
    public void enrich(Context context) {
        // 添加营养相关上下文
        context.put("nutritionStandard", loadCurrentStandard());
    }
    
    @Override
    public boolean supports(String domain) {
        return "nutrition".equals(domain);
    }
}
```

### 4. RuleDefinitionProvider

注册业务规则：

```java
@Component
public class BusinessRuleProvider implements RuleDefinitionProvider {
    @Override
    public List List<RuleDefinition> getRuleDefinitions() {
        return Arrays.asList(
            // 权限检查规则
            new PermissionRule(),
            // 审计日志规则
            new AuditLogRule()
        );
    }
}
```

## 快速开始

### 1. 添加依赖

```xml

<dependency>
    <groupId>com.yunxi</groupId>
    <artifactId>agent-business</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 实现业务 Agent

```java
@Component
@AgentType("nutrition-assistant")
public class NutritionAgent extends AbstractAgent {
    
    @Override
    protected void onInitialize() {
        // 初始化营养知识库
        loadNutritionDatabase();
    }
    
    @Override
    protected AgentResponse handleRequest(AgentRequest request) {
        // 处理营养相关请求
        String intent = classifyIntent(request.getMessage());
        switch (intent) {
            case "recipe-analysis":
                return analyzeRecipe(request);
            case "meal-planning":
                return planMeal(request);
            default:
                return generalNutritionQA(request);
        }
    }
}
```

### 3. 配置业务域

```yaml
agent:
  business:
    domains:
      - code: nutrition
        enabled: true
        config:
          standard-version: "2024"
          database-path: "${NUTRITION_DB_PATH}"
      - code: devops
        enabled: true
        config:
          deploy-timeout: 300
```

## 配置参考

```yaml
# 业务模块配置
agent:
  business:
    # 启用的业务域
    enabled-domains:
      - nutrition
      - devops
      - a2apipeline
    
    # 营养域配置
    nutrition:
      database-path: ${NUTRITION_DB_PATH}
      standard-version: "2024"
      
    # 运维域配置
    devops:
      kubernetes:
        config-path: ${KUBECONFIG_PATH}
      jenkins:
        url: ${JENKINS_URL}
        username: ${JENKINS_USER}
        token: ${JENKINS_TOKEN}
        
    # A2A流水线配置
    a2apipeline:
      max-concurrent-tasks: 10
      timeout-seconds: 3600

# 规则引擎集成
rule-engine:
  enabled: true
  providers:
    - com.yunxi.agent.business.rule.BusinessRuleProvider
```

## 与 agent-core 的关系

```
agent-business
    ├── 依赖: agent-core (框架核心)
    ├── 扩展: DomainContributor (业务域)
    ├── 扩展: SceneContributor (场景)
    ├── 扩展: ContextEnricher (上下文)
    ├── 扩展: RuleDefinitionProvider (规则)
    └── 使用: agent-text2sql (SQL生成)
```

## 目录结构

```
agent-business/
├── src/main/java/com/yunxi/agent/business/
│   ├── domain/           # 业务域实现
│   │   ├── nutrition/    # 营养域
│   │   ├── devops/       # 运维域
│   │   ├── a2apipeline/  # A2A流水线
│   │   └── reportquery/  # 报表查询
│   ├── spi/              # SPI 扩展实现
│   │   ├── DomainContributorImpl.java
│   │   ├── SceneContributorImpl.java
│   │   └── ContextEnricherImpl.java
│   ├── rule/             # 业务规则
│   │   ├── PermissionRule.java
│   │   └── AuditLogRule.java
│   └── service/          # 业务服务
├── src/main/resources/
│   ├── application.yml
│   └── domains/          # 业务域配置
└── pom.xml
```

## 开发规范

### 1. 业务域命名

- 使用小写字母和下划线
- 示例：`nutrition`, `devops`, `a2apipeline`

### 2. 场景命名

- 格式：`{domain}-{action}`
- 示例：`recipe-analysis`, `deploy-management`

### 3. Agent 实现

- 继承 `AbstractAgent`
- 使用 `@AgentType` 注解
- 实现业务特定的 `handleRequest` 方法

### 4. 规则注册

- 实现 `RuleDefinitionProvider`
- 业务规则放在 `rule` 包下
- 在 `getRuleDefinitions()` 中返回所有规则

## 注意事项

1. **依赖管理**：只依赖 agent-core，不依赖 agent-gateway/app
2. **SPI 注册**：扩展点实现类需要被 Spring 扫描到
3. **配置隔离**：各业务域配置使用前缀隔离
4. **错误处理**：业务异常需要转换为统一的 AgentException

## 依赖关系

```
agent-business
    ├── agent-core (框架核心)
    ├── agent-text2sql (SQL生成)
    ├── agent-rule-engine (规则引擎 SPI)
    └── milvus-sdk-java (向量检索)
```

**不依赖**：agent-gateway, agent-app
