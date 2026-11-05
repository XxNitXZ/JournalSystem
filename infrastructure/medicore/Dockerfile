# syntax=docker/dockerfile:1

# MediCore (Spring Boot backend) - production image.
#
# Build from the repository root (build context: the repository root,
# filtered by the root .dockerignore):
#   docker build -f infrastructure/medicore/Dockerfile -t medinexus-medicore:local .
#
# No configuration or credentials are baked into the image. The database
# connection is supplied when the container is started, through the
# environment variables DB_URL, DB_USERNAME and DB_PASSWORD
# (see infrastructure/database/README.md).

# ---- Stage 1: build the executable JAR with the Maven Wrapper and JDK 21 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Only what the Maven build needs (see .dockerignore).
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src

# Tests are not run here: the full-context tests need a PostgreSQL database,
# and building the image must not. Run them separately (mvnw test).
# The cache mount keeps Maven and the dependencies between builds; it is not
# part of any image layer.
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw --batch-mode --no-transfer-progress -DskipTests package \
 && cp target/*.jar /workspace/app.jar

# ---- Stage 2: minimal Java 21 runtime with only the application JAR ----
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Run as an unprivileged system user instead of root.
RUN addgroup -S -g 10001 medicore \
 && adduser -S -D -H -u 10001 -G medicore -s /sbin/nologin medicore

# Owned by root and read-only for the application user.
COPY --from=build /workspace/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

# Let the JVM use up to 75 % of the container's memory limit (default is 25 %).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
