# =============================================================================
# ShopPilot AI —— backend-java 镜像（多阶段构建）
# =============================================================================
# 依据：
#   PROJECT_STRUCTURE.md   §4    —— backend-java 目录下含 Dockerfile
#   SYSTEM_ARCHITECTURE.md §8.2  —— 凭证仅由环境变量注入，不烘焙进镜像
#   ACCEPTANCE_CRITERIA.md :81   —— 容器内 curl /actuator/health 供 healthcheck
#   ACCEPTANCE_CRITERIA.md :82   —— 需在 backend-java 容器内 `curl service-ai:8000`，
#                                   故运行阶段必须自带 curl
#
# 构建：docker build -t shoppilot/backend-java:0.1.0 backend-java
# 注：依赖下载走 BuildKit 的 /root/.m2 缓存挂载，重复构建不重下依赖。
# =============================================================================

# ---------- 构建阶段：Maven 打包（跳过测试；测试由 CI 负责，见 M1-T11）----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# ---------- 运行阶段：仅 JRE，最小体积 ----------
FROM eclipse-temurin:21-jre AS runtime

# curl：容器内健康检查 + 跨服务探活（ACCEPTANCE_CRITERIA :81 / :82）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/app/target/shoppilot-app-*.jar /app/app.jar

# 非 root 运行；/app/uploads 预留给上传卷（SYSTEM_ARCHITECTURE §8）
RUN groupadd -r shoppilot && useradd -r -g shoppilot shoppilot \
    && mkdir -p /app/uploads \
    && chown -R shoppilot:shoppilot /app
USER shoppilot

EXPOSE 8080
# shell 形式以便注入 JAVA_OPTS
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
