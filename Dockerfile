# ==============================================================================
# Stage 1: Build Environment (Compiles and packages the Spring Boot Fat JAR)
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Maven Wrapper and POM first to leverage Docker layer caching
COPY pom.xml mvnw ./
COPY .mvn .mvn

# Ensure execution permissions and Unix line endings for Windows host checkouts
RUN chmod +x ./mvnw && sed -i 's/\r$//' ./mvnw

# Copy project source code
COPY src src

# Build production application JAR (skip tests during container packaging)
RUN ./mvnw clean package -DskipTests -B

# ==============================================================================
# Stage 2: Hardened Production Runtime (Minimal footprint JRE image)
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runner

# Create dedicated non-root application user for container security (OWASP Docker Top 10)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy compiled JAR from build stage
COPY --from=builder --chown=appuser:appgroup /build/target/*.jar app.jar

# Run as non-privileged user
USER appuser

# Expose HTTPS port
EXPOSE 8443

# Production JVM flags: G1 Garbage Collector, 75% container memory cap, non-blocking entropy
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Healthcheck probe using Spring Boot Actuator
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- --no-check-certificate https://localhost:8443/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
