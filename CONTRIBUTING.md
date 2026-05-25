# 贡献指南

感谢您对 yunxi Agent Platform 项目的关注！我们欢迎各种形式的贡献，包括但不限于代码提交、问题反馈、文档改进等。

## 行为准则

请阅读我们的 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)，参与本项目即表示您同意遵守其中的行为准则。

## 如何贡献

### 1. 报告问题

- 使用 GitCode Issues 报告 Bug 或功能请求
- 提交问题时，请提供：
  - 清晰的标题和描述
  - 复现步骤
  - 预期行为 vs 实际行为
  - 环境信息（Java 版本、操作系统等）
  - 相关日志或截图

### 2. 代码贡献流程

1. **Fork 仓库**
   ```bash
   git clone https://gitcode.com/your-username/yunxi-agent-platform.git
   cd yunxi-agent-platform
   ```

2. **创建分支**
   ```bash
   git checkout -b feature/your-feature-name
   # 或修复 bug
   git checkout -b fix/issue-description
   ```

3. **开发**
   - 遵循项目的代码规范
   - 编写单元测试
   - 确保所有测试通过
   ```bash
   mvn clean test
   ```

4. **提交**
   ```bash
   git add .
   git commit -m "feat: 添加新功能"    # 功能
   # 或
   git commit -m "fix: 修复 XXX 问题"  # 修复
   ```

   提交信息格式：
   - `feat:` 新功能
   - `fix:` Bug 修复
   - `docs:` 文档更新
   - `refactor:` 代码重构
   - `test:` 测试相关
   - `chore:` 构建/工具变更

5. **推送并创建 PR**
   ```bash
   git push origin feature/your-feature-name
   ```

6. **创建 Pull Request**
   - 详细描述您的更改
   - 关联相关 Issue
   - 等待代码审查

### 3. 代码规范

#### Java 代码规范
- 遵循 Google Java Style Guide
- 使用构造器注入（避免 field @Autowired）
- 所有 public 方法必须有 Javadoc
- 复杂逻辑需要行级注释
- 类、方法、字段使用完整注释

#### 提交规范
- 使用语义化提交信息
- 一个提交只做一件事
- 提交信息描述清晰

### 4. 测试要求

- 新功能必须包含单元测试
- Bug 修复必须包含回归测试
- 保持测试覆盖率
- 测试代码同样需要遵循代码规范

### 5. 文档要求

- 新功能需要更新相关文档
- API 变更需要在 CHANGELOG 中记录
- 复杂逻辑需要添加使用示例

## 开发环境

### 环境要求
- JDK 17+
- Maven 3.8+
- MySQL 8.0+（开发测试）
- Redis 6.0+

### 快速开始
```bash
# 克隆项目
git clone https://gitcode.com/chenyao813/yunxi-agent-platform.git
cd yunxi-agent-platform

# 安装依赖
mvn clean install -DskipTests

# 运行测试
mvn test

# 启动应用
mvn spring-boot:run -pl agent-core
```

## 项目结构

```
yunxi-agent-platform/
├── agent-core/          # 核心框架
├── agent-business/      # 业务模块
├── agent-gateway/       # 网关模块
├── agent-rule-engine/  # 规则引擎
├── agent-text2sql/     # Text-to-SQL
├── agent-spi/          # SPI 接口
├── k8s/                # Kubernetes 部署
├── helm/               # Helm Chart
└── docs/               # 文档
```

## 许可证

参与本项目即表示您同意将您的贡献按照项目的 [LICENSE](LICENSE) 条款发布。

## 联系方式

- GitCode Issues: https://gitcode.com/chenyao813/yunxi-agent-platform/issues
- 邮箱: dev@yunxi.cn

## 致谢

感谢所有为 yunxi Agent Platform 做出贡献的开发者！
