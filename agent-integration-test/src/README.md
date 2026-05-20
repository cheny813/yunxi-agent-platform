# 顶层集成测试目录说明

## 📁 目录定位

这个 `src/test/` 目录位于项目根目录下，是 **yunxi-agent-platform** 项目的**顶层集成测试基础设施**。

## 🎯 设计目的

### 为什么放在根目录？
- **全局视角**：协调所有子模块间的协作测试
- **端到端验证**：模拟完整的业务场景流程
- **统一环境**：为所有模块提供一致的测试配置
- **简化执行**：从顶层运行完整的集成测试套件

## 🏗️ 目录结构

```
src/test/
├── java/io/yunxi/platform/integration/
│   ├── mock/                          # 模拟服务层
│   │   ├── MockFormApiService.java    # 模拟表单API服务
│   │   └── MockRecipeApiService.java  # 模拟食谱API服务
│   ├── CrossModuleIntegrationTest.java  # 跨模块集成测试
│   ├── EndToEndIntegrationTest.java     # 端到端测试
│   ├── ErrorRecoveryIntegrationTest.java # 错误恢复测试
│   └── [+1 files]                    # 更多集成测试
└── resources/
    └── application-integration-test.yml # 集成测试配置
```

## 📋 测试范围

### 1. 跨模块协作测试
- **概念识别** ↔ **用户画像构建** ↔ **智能会话处理** 的数据流
- 模块间通信协议和数据结构一致性
- 资源共享和并发控制的验证

### 2. 端到端业务场景
- **用户对话** → **概念识别** → **画像构建** → **智能回复** → **结果返回**
- SSE实时通信与外部API调用的结合
- 数据持久化与状态管理的完整性

### 3. 错误处理与恢复
- 单点故障时的系统稳定性
- 异常场景下的优雅降级
- 数据一致性和事务管理

## ⚙️ 配置说明

### 测试环境配置 (`application-integration-test.yml`)
- **嵌入式数据库**：H2内存数据库，避免环境依赖
- **模拟服务配置**：外部API的Mock实现
- **调试配置**：详细的日志和监控端点
- **缓存策略**：适合测试环境的缓存配置

## 🚀 运行方式

### 方式1：运行所有集成测试
```bash
# 在项目根目录执行
mvn test -Dtest=io.yunxi.platform.integration.*Test
```

### 方式2：运行特定测试类
```bash
mvn test -Dtest=CrossModuleIntegrationTest
mvn test -Dtest=EndToEndIntegrationTest
mvn test -Dtest=ErrorRecoveryIntegrationTest
```

### 方式3：使用IDE运行
- 直接在IDE中右击测试类运行
- 确保使用 `integration-test` profile

## 🔧 Mock服务说明

### MockFormApiService
- 模拟表单填写API的响应
- 返回预定义的测试数据格式
- 支持表单自动填充和验证逻辑

### MockRecipeApiService  
- 模拟食谱生成和配平API
- 提供标准的营养分析响应
- 支持食谱平衡度计算

## 🧪 测试场景示例

### 场景1：完整的用户定位流程
```java
testConceptToProfileToConversationWorkflow()
```
- 输入：用户描述身份的对话
- 流程：概念识别 → 画像构建 → 智能回复
- 验证：数据在模块间正确传递

### 场景2：实时通知与对话结合
```java
testSSEWithConversationIntegration()
```
- 启动SSE实时通道
- 执行长时间对话任务
- 验证进度通知和结果推送

### 场景3：错误恢复机制
```java
testErrorPropagationAndHandling()
```
- 模拟服务异常
- 验证系统不崩溃
- 检查错误恢复流程

## 📊 质量指标

- **模块覆盖率**：确保所有核心业务模块都有集成测试
- **数据一致性**：验证跨模块数据格式的统一性
- **异常覆盖率**：涵盖主要的错误场景
- **性能基准**：建立性能基准用于回归测试

## 🔄 维护指南

### 添加新的集成测试
1. 在 `integration/` 目录下创建新的测试类
2. 继承现有的测试基类或模式
3. 更新配置文件支持新的测试场景
4. 确保测试资源正确清理

### 配置变更
- 修改 `application-integration-test.yml` 添加新配置
- 更新Mock服务支持新的API调用
- 调整测试策略适应架构变化

### 持续集成
- 这些测试应该在CI/CD流程中自动运行
- 监控测试执行时间和成功率
- 定期更新测试数据适配业务变化

---

## 💡 注意事项

- 这些是**集成测试**，不是单元测试，执行时间较长
- 需要完整的Spring上下文启动
- 测试前确保所有依赖服务正常
- 定期清理测试产生的临时数据