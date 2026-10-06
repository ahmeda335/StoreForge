# syntax=docker/dockerfile:1

# ---- Build stage: full JDK + Maven wrapper; dependency layer cached separately from sources ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/storeforge-*.jar extract --layers --launcher --destination target/extracted

# ---- Runtime stage: JRE only, non-root, layers ordered from least to most frequently changing ----
FROM eclipse-temurin:21-jre
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
