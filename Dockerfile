# Multi-stage build for yunxi Agent Platform
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# Copy pom files first for better layer caching
COPY pom.xml .
COPY agent-rule-engine/pom.xml agent-rule-engine/
COPY agent-core/pom.xml agent-core/
COPY agent-business/pom.xml agent-business/
COPY agent-gateway/pom.xml agent-gateway/
COPY agent-app/pom.xml agent-app/
COPY agent-text2sql/pom.xml agent-text2sql/

# Download dependencies
RUN mvn dependency:go-offline -B

# Copy source code
COPY . .

# Build application
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine

# Install required packages
RUN apk add --no-cache curl tzdata

# Set timezone
ENV TZ=Asia/Shanghai

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy JAR files from builder
COPY --from=builder /build/agent-app/target/*.jar app.jar

# Create data directory for SQLite sessions
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:40001/actuator/health || exit 1

# Expose ports
EXPOSE 40001 9090

# JVM options for container environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
