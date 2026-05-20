# 10. 技能系统

技能（Skill）是 yunxi Agent Platform 的核心概念，用于封装特定领域的能力。

## 技能系统理论

### 什么是技能（Skill）

**技能**是可复用的能力单元，类似于编程中的函数或微服务：
- **输入**：参数（Map<String, Object>）
- **处理**：业务逻辑
- **输出**：结果（SkillResult）

**技能 vs 工具 vs MCP**：
| 概念 | 说明 | 使用场景 |
|------|------|----------|
| **Skill** | 框架内部的能力单元 | 业务逻辑封装 |
| **Tool** | Agent 可调用的功能 | 外部能力集成 |
| **MCP** | 标准化的工具协议 | 跨服务工具调用 |

### 技能的优势

| 优势 | 说明 |
|------|------|
| **复用性** | 一次开发，多处使用 |
| **可维护** | 独立开发、测试、部署 |
| **可扩展** | 动态加载新技能 |
| **可组合** | 技能可以互相调用 |

---

## 技能概述

### 什么是技能

技能是可复用的能力单元，可以被 Agent 调用以完成特定任务。

### 技能类型

| 类型 | 说明 | 示例 |
|------|------|------|
| 内置技能 | 框架预置的技能 | 文件操作、数据库查询 |
| 自定义技能 | 用户开发的技能 | 营养分析、报表生成 |
| 第三方技能 | 从 Git 仓库加载 | 社区贡献的技能 |

---

## 技能加载

### 加载来源

技能从两个目录加载：
- **classpath**: `src/main/resources/skills/` - 预置技能
- **filesystem**: `./skills/` - 用户扩展技能

### 技能加载原理

```
┌─────────────────────────────────────────┐
│  技能加载流程                            │
├─────────────────────────────────────────┤
│                                         │
│  1. 扫描技能目录                          │
│     - classpath:skills/                  │
│     - filesystem:./skills/               │
│                                         │
│  2. 解析 SKILL.md                        │
│     - 读取元数据                          │
│     - 验证格式                           │
│                                         │
│  3. 注册到 SkillBox                      │
│     - 建立名称映射                        │
│     - 初始化技能实例                       │
│                                         │
│  4. 技能就绪                             │
│     - 可被 Agent 调用                     │
│                                         │
└─────────────────────────────────────────┘
```

### 启用配置

```yaml
skill-box:
  enabled: true
  classpath-enabled: true
  classpath-path: skills
  filesystem-path: ./skills
```

---

## 使用技能

### 在 Agent 中调用

```java
@Component
public class NutritionAgent {
    
    @Autowired
    private SkillBox skillBox;
    
    public AgentResponse handleRequest(AgentRequest request) {
        // 调用技能
        SkillResult result = skillBox.execute("nutrition-analysis", 
            Map.of("recipe", request.getMessage()));
        
        return AgentResponse.builder()
            .message(result.getOutput())
            .build();
    }
}
```

**调用原理**：
```
Agent.handleRequest()
    ↓
SkillBox.execute(skillName, params)
    ↓
SkillRegistry.find(skillName)
    ↓
Skill.execute(params)
    ↓
返回 SkillResult
```

---

## 开发技能

### 技能定义

```yaml
# SKILL.md
name: nutrition-analysis
description: 分析食谱营养成分
category: nutrition
version: 1.0.0
author: yunxi
```

**元数据字段说明**：
| 字段 | 说明 | 必需 |
|------|------|------|
| name | 技能唯一标识 | 是 |
| description | 技能描述 | 是 |
| category | 分类 | 否 |
| version | 版本号 | 是 |
| author | 作者 | 否 |

### 技能实现

```java
@Component
public class NutritionAnalysisSkill implements Skill {
    
    @Override
    public String getName() {
        return "nutrition-analysis";
    }
    
    @Override
    public SkillResult execute(Map<String, Object> input) {
        String recipe = (String) input.get("recipe");
        
        // 分析逻辑
        NutritionReport report = analyze(recipe);
        
        return SkillResult.builder()
            .output(formatReport(report))
            .build();
    }
}
```

**Skill 接口规范**：
```java
public interface Skill {
    // 技能唯一名称
    String getName();
    
    // 执行技能
    SkillResult execute(Map<String, Object> input);
    
    // 可选：获取输入参数定义
    default List<ParameterDef> getParameters() { return List.of(); }
    
    // 可选：获取输出定义
    default OutputDef getOutput() { return new OutputDef(); }
}
```

---

## 技能目录

### 内置技能

| 技能名称 | 说明 |
|----------|------|
| file-manager | 文件操作 |
| database-query | 数据库查询 |
| calculator | 计算器 |

### 业务技能

| 技能名称 | 说明 | 领域 |
|----------|------|------|
| nutrition-recipe | 食谱分析 | 营养 |
| nutrition-scoring | 营养评分 | 营养 |
| report-generator | 报表生成 | 通用 |

---

**上一页**: [09. API 参考](./09-api-reference.md)  
**下一页**: [11. 最佳实践 →](./11-best-practices.md)
