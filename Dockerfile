# ---- Build stage: full JDK + Maven wrapper, produces the jar ----
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /workspace

# Copy only the build definition first and download dependencies. Docker caches this layer,
# so changing source code doesn't re-download every dependency.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests are skipped here because the integration tests need Docker (Testcontainers);
# CI runs the full `./mvnw verify` instead.
RUN ./mvnw -B -q package -DskipTests && cp target/leaderboard-service-*.jar app.jar

# ---- Runtime stage: JRE only (smaller image, no compiler or build tools) ----
FROM eclipse-temurin:21.0.13_9-jre-noble

# Run as an unprivileged system user, not root.
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app

EXPOSE 8080
# The JVM sizes its heap from the container's memory limit; use up to 75% of it (the default is 25%).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
