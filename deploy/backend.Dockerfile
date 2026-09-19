# Backend image: one Spring Boot jar (ADR-0010). Build context is the repository root.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src/backend
COPY backend/ ./
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --home /app qms && mkdir -p /var/lib/qms/keys && chown -R qms /var/lib/qms
WORKDIR /app
COPY --from=build /src/backend/build/libs/*.jar /app/app.jar
USER qms
EXPOSE 8080
# The same image runs migrations as a separate step: `--spring.profiles.active=migrate` (FR-OPS-020).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
