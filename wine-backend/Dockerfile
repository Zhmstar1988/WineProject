# ============================================================
# 智能分酒系统 - Docker 镜像构建文件
# 多阶段构建：Maven 编译 → JRE 运行
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build
COPY pom.xml .
# 下载依赖（利用 Docker 缓存层）
RUN mvn dependency:go-offline -B

COPY src ./src
# 编译打包，跳过测试
RUN mvn package -DskipTests -B

# ------------------------------------------------------------
# 运行阶段：精简 JRE 镜像
# ------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 复制构建产物
COPY --from=builder /build/target/*.jar app.jar

# 时区设置
ENV TZ=Asia/Singapore
RUN apk add --no-cache tzdata && cp /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 暴露端口
EXPOSE 8080

# JVM 参数（容器环境优化）
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom"

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
