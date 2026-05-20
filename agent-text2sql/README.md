# Agent Text-to-SQL Engine

这是一个基于 LLM 的 Text-to-SQL 引擎，使用向量检索、Few-shot Learning、Self-Consistency 和投票机制提高 SQL 生成的准确性。

## 核心功能

### 1. Schema 生成 (SchemaGenerator)
- 从 MCP 数据库自动生成数据库 Schema
- 推断表之间的外键关系
- 支持缓存以提高性能

### 2. 列检索 (ColumnRetriever)
- 基于向量相似度检索相关列
- 使用 Milvus 存储列的嵌入向量
- 支持索引和检索操作

### 3. Few-shot 示例管理 (FewShotManager)
- 管理 Few-shot 示例
- 基于相似度检索相关示例
- 支持自动更新和限制数量

### 4. SQL 生成 (SqlGenerator)
- 使用 LLM 生成 SQL
- 支持 Few-shot Learning
- 支持 Self-Consistency（生成多个候选）

### 5. SQL 对齐 (SqlAligner)
- 自动修复常见 SQL 错误
- 修复字段名错误
- 添加缺失的条件和 LIMIT

### 6. SQL 投票 (SqlVoter)
- 执行多个候选 SQL
- 比较结果集并选择最优 SQL
- 支持自定义执行器

## 配置

将配置文件 `application-text2sql.yml` 中的配置添加到你的 `application.yml` 中。

关键配置项：
- `text2sql.candidate-count`: 候选 SQL 数量
- `text2sql.use-voting`: 是否启用投票
- `text2sql.use-alignment`: 是否启用 SQL 对齐
- `milvus.host/port`: Milvus 地址
- `spring.ai.openai.api-key`: OpenAI API 密钥

## 组件依赖

- **MCP 数据库客户端**: 用于查询数据库 Schema 和执行 SQL
- **Milvus**: 用于存储和检索列的嵌入向量
- **EmbeddingService**: 用于生成文本的嵌入向量
- **ChatClient (Spring AI)**: 用于生成 SQL

## 性能优化

1. **Schema 缓存**: SchemaGenerator 使用 Spring Cache 缓存 Schema
2. **向量检索**: 使用 Milvus 进行高效的向量相似度搜索
3. **Few-shot 缓存**: FewShotManager 在内存中缓存示例
4. **并行执行**: 可以并行生成多个候选 SQL

## 扩展性

- 自定义 SqlExecutor 接口实现不同的 SQL 执行器
- 自定义相似度计算方法
- 添加更多的 SQL 对齐规则
- 自定义投票策略

## 注意事项

1. 确保 Milvus 已正确配置并运行
2. 确保配置了有效的 OpenAI API 密钥
3. 首次使用前需要索引数据库列信息
4. MCP 数据库客户端需要正确配置

## 示例

查看 `application-text2sql.yml` 了解完整配置示例。

## 作者

yunxi-agent-platform

## 版本

1.0.0