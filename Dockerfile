# syntax=docker/dockerfile:1

# =============================================================================
# Stage 1: build the fat JAR
# =============================================================================
FROM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /build

# Copy only the POM first so the dependency layer is cached and re-used
# until pom.xml changes (source edits do not invalidate it).
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

# Now copy the sources and package (tests are executed in the Jenkins "Test" stage).
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# =============================================================================
# Stage 2: minimal runtime image (JRE only, non-root user)
# =============================================================================
FROM eclipse-temurin:17-jre-alpine

# Create an unprivileged user and group with fixed IDs
RUN addgroup -S -g 1001 appgroup \
    && adduser -S -u 1001 -G appgroup -H -s /sbin/nologin appuser

WORKDIR /app

COPY --from=build --chown=appuser:appgroup /build/target/calculator-devops.jar /app/app.jar

# Run as non-root
USER appuser

ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/actuator/health" || exit 1

# "exec" makes the JVM PID 1 so it receives SIGTERM from `docker stop`
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
