# SJHERP 后端生产镜像：源码构建可执行 jar（Flyway 随应用启动自动迁移）
# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY server/ /src/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package -pl sjherp-app -am \
    && JAR="$(find sjherp-app/target -maxdepth 1 -type f -name 'sjherp-app-*.jar' ! -name '*.original' | head -n 1)" \
    && test -n "$JAR" \
    && cp "$JAR" /src/app.jar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 sjherp \
    && useradd --system --uid 10001 --gid 10001 --home /app --shell /usr/sbin/nologin sjherp
WORKDIR /app
COPY --from=build --chown=sjherp:sjherp /src/app.jar /app/app.jar
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
    CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
