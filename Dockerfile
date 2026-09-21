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

FROM eclipse-temurin:17-jdk-alpine AS jre-build
RUN "$JAVA_HOME/bin/jlink" \
    --add-modules java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.unsupported,jdk.zipfs \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --compress=2 \
    --output /opt/java-minimal

# --- Runtime stage -----------------------------------------------------
# Slim JRE (not full JDK) — smaller image, smaller attack surface, and we
# don't need a compiler at runtime.
FROM alpine:3.22 AS runtime
WORKDIR /app

# Run as a dedicated non-root user rather than the image's default root.
RUN apk add --no-cache ca-certificates \
    && addgroup -S app \
    && adduser -S app -G app
COPY --from=jre-build /opt/java-minimal /opt/java
COPY --chown=app:app --from=build /build/target/app.jar ./app.jar
USER app

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

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

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
