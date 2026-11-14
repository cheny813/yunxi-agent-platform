# ============================================================
# yunxi-app 多阶段构建（构建上下文为仓库根目录）
# 用法：docker build -f yunxi-app/Dockerfile -t yunxi-agent-platform:latest .
# ============================================================

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先复制所有 pom 以利用 Maven 构建缓存
COPY pom.xml .
COPY yunxi-definition/pom.xml yunxi-definition/pom.xml
COPY yunxi-config/pom.xml yunxi-config/pom.xml
COPY yunxi-observability/pom.xml yunxi-observability/pom.xml
COPY yunxi-capability/pom.xml yunxi-capability/pom.xml
COPY yunxi-runtime/pom.xml yunxi-runtime/pom.xml
COPY yunxi-ingress/pom.xml yunxi-ingress/pom.xml
COPY yunxi-fabric/pom.xml yunxi-fabric/pom.xml
COPY yunxi-intelligence/pom.xml yunxi-intelligence/pom.xml
COPY yunxi-app/pom.xml yunxi-app/pom.xml

# 复制全部源码
COPY . .

# 构建 yunxi-app 及其依赖模块（-am 自动构建上游模块）
RUN mvn -pl yunxi-app -am -DskipTests clean package

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# 运行时仅需产物 jar
COPY --from=build /build/yunxi-app/target/yunxi-app-*.jar app.jar

# 平台统一入口端口
EXPOSE 40001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
