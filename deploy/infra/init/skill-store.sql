-- yunxi 技能仓库表结构（复用调度 MySQL）
--
-- 对应框架 io.agentscope.extensions.jdbc 的 JdbcAgentSkillRepository。
-- 框架构造器不建表；平台装配（SkillRepositoryConfiguration）在 dialect build() 阶段
-- enableSkillTables=true 会自动执行 DDL 建表，本文件为幂等预建兜底，用于部署期显式管控表结构。
-- 表前缀默认 agentscope_（与框架 AbstractJdbcDialect 默认前缀一致）；请勿改前缀，否则 schema 校验失败。
-- 在调度 MySQL 中执行本文件即可（与 deploy/infra/init/quartz-tables.sql 同库，互不冲突）。
-- 启用：yunxi.skill.jdbc.enabled=true + yunxi.skill.jdbc.url 指向本 MySQL（与调度库同连接参数）。

CREATE TABLE IF NOT EXISTS agentscope_skills (
  id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
  namespace     VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL DEFAULT 'default' COMMENT '技能命名空间，同一调度库内隔离不同来源/租户的技能',
  name          VARCHAR(255) NOT NULL COMMENT '技能名称（同一 namespace 内唯一）',
  description   TEXT         NOT NULL COMMENT '技能描述',
  skill_content LONGTEXT     NOT NULL COMMENT '技能内容（SKILL.md 等序列化后的完整定义）',
  source        VARCHAR(255) NOT NULL COMMENT '技能来源标识（如 git / jdbc / builtin）',
  metadata_json LONGTEXT     NULL COMMENT '技能元数据（JSON，可空）',
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  UNIQUE KEY uk_namespace_name (namespace, name)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='技能定义主表（覆盖式 upsert，同一 namespace+name 唯一，无历史版本行）';

CREATE TABLE IF NOT EXISTS agentscope_skill_resources (
  id               BIGINT       NOT NULL COMMENT '关联 agentscope_skills.id',
  namespace        VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL DEFAULT 'default' COMMENT '技能命名空间，冗余存储以便按命名空间检索',
  resource_path    VARCHAR(500) NOT NULL COMMENT '资源相对路径（如技能目录内的子文件路径）',
  resource_content LONGTEXT     NOT NULL COMMENT '资源文件内容',
  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id, resource_path),
  INDEX idx_namespace (namespace),
  FOREIGN KEY (id) REFERENCES agentscope_skills (id) ON DELETE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT='技能附属资源表：存储技能目录内的子文件（代码/示例/附件等）';
