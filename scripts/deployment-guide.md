# 🚀 系统部署与运维指南

## 1. 环境要求

### 1.1 硬件要求
- **内存**: 最低4GB，推荐8GB以上
- **CPU**: 2核以上，推荐4核
- **磁盘空间**: 最少10GB可用空间

### 1.2 软件环境
- **JDK**: 17+ (推荐OpenJDK 17)
- **Maven**: 3.6+
- **数据库**: MySQL 8.0+/PostgreSQL 14+
- **缓存**: Redis 6.0+
- **系统**: Linux (CentOS 7+/Ubuntu 18.04+) 或 Docker

### 1.3 端口要求
```yaml
服务端口:
  - API服务: 8080 (可配置)
  - 管理端口: 9090 (可配置)
  - 数据库: 3306/5432
  - Redis: 6379
```

## 2. 部署步骤

### 2.1 源码编译
```bash
# 克隆代码
cd /opt

# 编译打包
mvn clean package -DskipTests

# 查看产物
ls target/*.jar
```

### 2.2 配置环境变量
```bash
# 创建环境配置
cat > /opt/yunxi-agent-platform/.env << EOF
APP_NAME=yunxi-agent-platform
APP_VERSION=1.0.0
JAVA_HOME=/usr/lib/jvm/java-17-openjdk
LOG_PATH=/var/log/yunxi-agent
DATA_PATH=/var/data/yunxi-agent
EOF

# 加载环境变量
source /opt/yunxi-agent-platform/.env
```

### 2.3 数据库初始化
```sql
-- 创建数据库
CREATE DATABASE IF NOT EXISTS `yunxi_agent` DEFAULT CHARACTER SET utf8mb4;

-- 创建用户（可选）
CREATE USER 'yunxi_agent'@'%' IDENTIFIED BY 'secure_password';
GRANT ALL PRIVILEGES ON yunxi_agent.* TO 'yunxi_agent'@'%';

-- 重启应用后会自动创建表结构
-- 或手动执行DDL (检查sql目录)
```

### 2.4 启动应用
```bash
# 方式1: 直接启动
java -jar target/yunxi-agent-platform-*.jar \
  --spring.profiles.active=prod \
  --server.port=8080 \
  --management.server.port=9090

# 方式2: 使用启动脚本
./scripts/startup.sh
```

## 3. 系统配置

### 3.1 应用配置 (application-prod.yml)
```yaml
server:
  port: 8080
  servlet:
    context-path: /
  tomcat:
    max-threads: 200
    min-spare-threads: 20

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/yunxi_agent
    username: yunxi_agent
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  
  redis:
    host: localhost
    port: 6379
    password: ${REDIS_PASSWORD}
    database: 0
    timeout: 3000ms
    lettuce:
      pool:
        max-active: 20
        max-wait: 1000ms

# JVM参数
jvm:
  options: "-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 性能监控
monitoring:
  enabled: true
  metrics:
    prometheus:
      enabled: true
  health:
    enabled: true
```

### 3.2 安全配置
```yaml
security:
  basic:
    enabled: false
  cors:
    allowed-origins: "*"
    allowed-methods: "GET,POST,PUT,DELETE,OPTIONS"
    allowed-headers: "*"
  
management:
  endpoint:
    health:
      show-details: when_authorized
    metrics:
      enabled: true
    prometheus:
      enabled: true
  endpoints:
    web:
      exposure:
        include: "health,info,metrics,prometheus"
```

## 4. 运维监控

### 4.1 健康检查端点
```bash
# 检查应用健康状态
curl http://localhost:9090/actuator/health

# 查看应用信息
curl http://localhost:9090/actuator/info

# Prometheus监控指标
curl http://localhost:9090/actuator/prometheus
```

### 4.2 性能监控指标
- **API响应时间**: http_server_requests_seconds
- **JVM内存使用**: jvm_memory_used_bytes
- **GC统计**: jvm_gc_pause_seconds
- **线程池状态**: executor_pool_size
- **数据库连接池**: hikaricp_connections_active

### 4.3 日志配置示例
```yaml
logging:
  level:
    com.yunxi: INFO
    org.springframework.web: INFO
    com.zaxxer.hikari: WARN
  file:
    name: /var/log/yunxi-agent/application.log
    rotate: true
    max-size: 100MB
    max-history: 30
  pattern:
    file: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
    console: "%d{HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

## 5. 备份与恢复

### 5.1 数据库备份脚本
```bash
#!/bin/bash
# backup-database.sh

BACKUP_DIR="/var/backup/yunxi-agent"
DATE=$(date +%Y%m%d_%H%M%S)

# 创建备份目录
mkdir -p $BACKUP_DIR

# 备份数据库
mysqldump -u yunxi_agent -p yunxi_agent > $BACKUP_DIR/db_backup_$DATE.sql

# 压缩备份文件
gzip $BACKUP_DIR/db_backup_$DATE.sql

# 清理7天前的备份
find $BACKUP_DIR -name "*.gz" -mtime +7 -delete

echo "数据库备份完成: $BACKUP_DIR/db_backup_$DATE.sql.gz"
```

### 5.2 配置文件备份
```bash
# 备份应用配置
tar -czf /var/backup/config_$(date +%Y%m%d).tar.gz \
  /opt/yunxi-agent-platform/config/
```

## 6. 故障排查

### 6.1 常见问题诊断

#### 启动失败
```bash
# 检查端口占用
netstat -tulpn | grep :8080

# 检查Java进程
ps aux | grep java

# 查看启动日志
tail -f /var/log/yunxi-agent/application.log
```

#### 性能问题排查
```bash
# 查看系统资源使用
top -p $(pgrep -f yunxi-agent)

# 检查GC状态
jstat -gc $(pgrep -f yunxi-agent) 5s

# 生成线程转储
jstack $(pgrep -f yunxi-agent) > thread_dump.txt
```

#### 内存泄漏检测
```bash
# 生成堆内存快照
jmap -dump:live,format=b,file=heapdump.hprof $(pgrep -f yunxi-agent)

# 分析堆转储文件
# 使用Eclipse Memory Analyzer (MAT)或jhat
```

### 6.2 应急响应流程

#### 服务不可用
1. **检查应用状态**: `curl http://localhost:9090/actuator/health`
2. **查看日志**: `tail -100f /var/log/yunxi-agent/application.log`
3. **检查资源**: `top`, `free -h`, `df -h`
4. **重启服务**: `systemctl restart yunxi-agent`

#### 响应时间变慢
1. **检查数据库**: `SHOW PROCESSLIST;`
2. **查看慢查询**: `SHOW SLOW_QUERIES;`
3. **检查缓存命中率**: Redis `INFO stats`
4. **分析线程池**: 访问 `/actuator/metrics/thread.pool`

## 7. 性能调优

### 7.1 数据库优化
```sql
-- 添加索引示例
ALTER TABLE api_log ADD INDEX idx_api_name (api_name);
ALTER TABLE api_log ADD INDEX idx_create_time (create_time);

-- 定期优化表统计
ANALYZE TABLE api_log;
```

### 7.2 JVM调优参数
```bash
# 生产环境推荐参数
java -jar app.jar \
  -Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -XX:InitiatingHeapOccupancyPercent=45 \
  -XX:+PrintGCDetails \
  -XX:+PrintGCDateStamps \
  -Xloggc:/var/log/yunxi-agent/gc.log
```

### 7.3 系统级优化
```bash
# 增大文件描述符限制
echo "* soft nofile 65536" >> /etc/security/limits.conf
echo "* hard nofile 65536" >> /etc/security/limits.conf

# 网络参数优化
echo 'net.core.somaxconn = 1024' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_max_syn_backlog = 1024' >> /etc/sysctl.conf
sysctl -p
```

## 8. 监控告警

### 8.1 Prometheus监控指标配置
```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'yunxi-agent'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:9090']
    scrape_interval: 15s
```

### 8.2 Grafana仪表板示例
```json
{
  "panels": [
    {
      "title": "API响应时间",
      "metrics": "rate(http_server_requests_seconds_sum[5m]) / rate(http_server_requests_seconds_count[5m])",
      "threshold": "0.5"
    },
    {
      "title": "JVM内存使用",
      "metrics": "jvm_memory_used_bytes / jvm_memory_max_bytes",
      "threshold": "0.8"
    }
  ]
}
```

### 8.3 告警规则示例
```yaml
# alert-rules.yml
groups:
- name: yunxi-agent-alerts
  rules:
  - alert: HighErrorRate
    expr: rate(http_server_requests_errors_total[5m]) > 0.1
    for: 2m
    labels:
      severity: warning
    annotations:
      summary: "API错误率过高"
      
  - alert: HighMemoryUsage
    expr: jvm_memory_used_bytes / jvm_memory_max_bytes > 0.9
    for: 2m
    labels:
      severity: critical
```

## 9. 版本升级

### 9.1 滚动升级流程
1. 备份当前版本和数据
2. 停止旧版本服务
3. 部署新版本应用
4. 验证新版本功能
5. 切换流量到新版本

### 9.2 数据库迁移
```bash
# 使用Flyway进行数据库迁移
./mvnw flyway:migrate

# 或手动执行迁移脚本
mysql -u yunxi_agent -p yunxi_agent < migration/v1.1.0.sql
```

## 10. 应急恢复

### 10.1 灾难恢复计划
- **备份策略**: 每日全量备份 + 增量备份
- **恢复时间目标 (RTO)**: < 4小时
- **恢复点目标 (RPO)**: < 1小时数据丢失

### 10.2 灾难恢复步骤
1. 恢复最新数据库备份
2. 恢复配置文件
3. 启动应用服务
4. 验证服务功能
5. 数据一致性检查

---

**技术支持**
- GitCode Issues: https://gitcode.com/chenyao813/yunxi-agent-platform/issues
- 项目文档: https://gitcode.com/chenyao813/yunxi-agent-platform/tree/main/docs
- 运维文档更新: 每次部署后更新此文档

**⚠️ 注意事项**
- 生产环境操作前必须备份数据
- 更改配置后需要重启服务生效
- 定期检查日志和监控告警
- 保持环境一致性（开发/测试/生产）