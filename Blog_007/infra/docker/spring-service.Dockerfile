# syntax=docker/dockerfile:1
# =====================================================================
# ONE Dockerfile for every Spring Boot service (content, gateway, ingestion, assistant).
# Designed and developed by Ankur Kumar · © 2026 Ravenclaw_007 LLC
#
# Build the jar on the host first (fast, uses your ~/.m2 cache), then:
#   docker build -f infra/docker/spring-service.Dockerfile \
#     --build-arg SERVICE=content-service \
#     --build-arg JAR_FILE=services/content-service/target/content-service-0.1.0-SNAPSHOT.jar \
#     -t pulse/content-service:0.1.0 .
#
# Two stages ("multi-stage build"):
#   1. extract  – unpack the fat jar into LAYERS (dependencies change rarely, our code often)
#   2. runtime  – copy the layers in order of how rarely they change → Docker re-uses cached layers,
#                 so a code change re-ships only the small "application" layer (KBs, not 60 MB).
# =====================================================================
ARG JRE_IMAGE=eclipse-temurin:25.0.5.1_2-jre-noble

FROM ${JRE_IMAGE} AS extract
WORKDIR /build
ARG JAR_FILE
COPY ${JAR_FILE} app.jar
# Spring Boot's "tools" jar mode splits the jar into layers + a small launcher.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM ${JRE_IMAGE} AS runtime
ARG SERVICE=service
LABEL org.opencontainers.image.title="pulse-${SERVICE}" \
      org.opencontainers.image.description="Pulse ${SERVICE} (Spring Boot)" \
      org.opencontainers.image.vendor="Ravenclaw_007 LLC" \
      org.opencontainers.image.authors="Ankur Kumar" \
      org.opencontainers.image.source="https://github.com/Ankur42177/The-Dark-Algorithm"
# Never run as root: a container escape as root is far worse than as an unprivileged user.
RUN groupadd --system --gid 10001 pulse && useradd --system --uid 10001 --gid pulse --no-create-home --shell /usr/sbin/nologin pulse
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER 10001:10001
# Container-aware JVM: size the heap from the container's memory LIMIT (75 %), not the node's RAM,
# and exit (so Kubernetes restarts the pod) instead of limping on after an OutOfMemoryError.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# The exploded layout has no app.jar: Spring Boot's JarLauncher starts the app from the layer folders.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
