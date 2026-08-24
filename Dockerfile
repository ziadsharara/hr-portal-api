# --- Build stage -----------------------------------------------------
# Maven + JDK 17 image, used only to compile and package the jar. Not
# present in the final image.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy the pom first so Docker can cache the dependency download layer
# and only re-run it when pom.xml actually changes, not on every source edit.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests \
    && cp target/hr-portal-api-*.jar target/app.jar

# --- Runtime stage -----------------------------------------------------
# Slim JRE (not full JDK) — smaller image, smaller attack surface, and we
# don't need a compiler at runtime.
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Run as a dedicated non-root user rather than the image's default root.
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /build/target/app.jar ./app.jar
RUN chown app:app ./app.jar
USER app

# Actual environment-specific config (DB_URL, DB_USERNAME, DB_PASSWORD,
# SPRING_PROFILES_ACTIVE, etc.) is supplied at run time via env vars —
# see docker-compose.yml locally and the ECS task definition in AWS.
# Nothing environment-specific is baked into this image.
ENV SERVER_PORT=8080
EXPOSE 8080

# Container-level health check hits the Actuator health endpoint added in
# application.properties. Note the /api prefix: server.servlet.context-path
# applies to actuator endpoints too. wget is available on the alpine base.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -q -O- http://127.0.0.1:${SERVER_PORT}/api/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
