# syntax=docker/dockerfile:1

# ---- Build stage: full JDK + Maven wrapper; dependency layer cached separately from sources ----
# Exact versions: a floating tag (21-jre) silently used a stale cached image in P14 (EXP-022). Dependabot proposes bumps.
FROM eclipse-temurin:21.0.12.1_1-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
# lombok.config sets fluent accessors (e.code()): without it the sources do not compile (missing since P8, found in P14).
COPY lombok.config ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/storeforge-*.jar extract --layers --launcher --destination target/extracted

# ---- Runtime stage: JRE only, non-root, layers ordered from least to most frequently changing ----
FROM eclipse-temurin:21.0.12.1_1-jre
# pebble (Canonical's service manager) ships in the base image but is never run here (java is PID 1);
# removing it drops its Go-stdlib CVEs and attack surface instead of ignoring them (P14 scan).
RUN rm -f /usr/bin/pebble
RUN groupadd --system storeforge && useradd --system --gid storeforge --no-create-home storeforge
WORKDIR /app
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
USER storeforge
EXPOSE 8080 8081
# Size the heap from the container memory limit instead of the host's RAM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# exec form: java is PID 1 and receives SIGTERM directly -> graceful shutdown works.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
