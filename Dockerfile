# Multi-stage production build for the standalone moviesapp-server repo.
#
# Stage 1 builds the Spring Boot fat jar with Gradle.
# Stage 2 runs only the jar on a JRE image as a non-root user.

# Stage 1 — build
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src
COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY settings.gradle.kts settings.gradle.kts
COPY build.gradle.kts build.gradle.kts
COPY gradle.properties gradle.properties
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

# Stage 2 — runtime
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app && useradd --system --gid app --uid 1001 app
WORKDIR /app
COPY --from=build /src/build/libs/*.jar /app/server.jar
USER app
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
