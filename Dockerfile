# ==========================================================================
# 多阶段构建：builder 里跑 Maven，runtime 只带 JRE
# ==========================================================================
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先只拷 pom，把依赖层缓存下来，源码变更时不必重新下载依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC" \
    SPRING_PROFILES_ACTIVE=docker

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY --from=builder /build/target/high-concurrency-mall.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
