# 后端镜像：多阶段构建。
#
# 为什么用多阶段：构建需要 Maven + 全部依赖（几百 MB），而运行只需要 JRE 与产物。
# 单阶段镜像会把 Maven 仓库、源码和构建缓存一起带进运行环境，既大又扩大了攻击面。
#
# 为什么运行阶段不直接 `mvn exec:java`：生产镜像里不应该有构建工具，
# 而且 exec 会在每次启动时重新解析依赖，启动时间与可用性都不可控。
# 这里显式把运行时依赖复制到 libs/，用 `java -cp` 启动——启动路径固定、可预测。
#
# 构建（在仓库根目录执行）：
#   docker build -t bid-arena-backend .
# 或直接用 compose：`docker compose up -d backend`（见 docker-compose.yml）。

# ---------------------------------------------------------------------------
# 阶段 1：构建
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先只复制 pom 并预热依赖，让“依赖未变”时这一层能命中缓存。
# 但 pom 里的测试依赖（Testcontainers 等）不参与运行，用 -DincludeScope=runtime 只取运行时。
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# 再复制源码与迁移。迁移脚本在仓库根目录 db/migration，经 pom 的 <resources> 打进 classpath，
# 因此必须一并复制，否则容器里的 Flyway 找不到 V1~V4（见 DECISIONS.md D-2）。
COPY src ./src
COPY db ./db
RUN mvn -B -q -DskipTests package \
 && mvn -B -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/libs

# ---------------------------------------------------------------------------
# 阶段 2：运行
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 非 root：容器被攻破时，降低对宿主机与同网段其它容器的破坏面。
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin bidarena

COPY --from=build /workspace/target/bid-arena-core-0.1.0-SNAPSHOT.jar /app/app.jar
COPY --from=build /workspace/target/libs /app/libs

USER bidarena
EXPOSE 8080 8090

# 用 -cp 而不是 -jar：项目没有打 fat jar，依赖在 libs/ 下（见 pom.xml 的构建说明）。
# 环境变量（DB_URL / JWT_SECRET / SERVER_PORT / AGENT_SERVER_PORT 等）由 compose 注入，
# 与本机运行读取的是同一套变量（bootstrap/Env）。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "/app/app.jar:/app/libs/*", "com.bidarena.Application"]
