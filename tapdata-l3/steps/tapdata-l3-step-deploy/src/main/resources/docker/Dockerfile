# ============================================================================
# Default Dockerfile for tapdata-l3-step-deploy
#
# Assumptions about the artifact tar.gz layout:
#   artifacts/tapdata.tar.gz
#   ├── apps/                  # application dir (tm.jar, ie.jar, agents, etc.)
#   │   ├── lib/
#   │   ├── conf/
#   │   ├── connectors/
#   │   └── bin/start.sh
#   ├── docker-entrypoint.sh   # optional
#   └── (others)
#
# The deploy step extracts the tarball and mounts apps/ into the container.
# Users can override this Dockerfile via the dockerfile_path parameter.
# ============================================================================

# Stage 1: Base runtime — JDK 17
FROM eclipse-temurin:17-jdk-jammy AS runtime

# Install curl (healthchecks / startup probes)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Application directory
WORKDIR /tapdata/apps

# Copy artifact contents (tapdata-deploy step extracts the tarball to build/ first)
COPY build/ /tapdata/

# Ensure start scripts are executable
RUN find /tapdata -name "*.sh" -type f -exec chmod +x {} \; 2>/dev/null || true

# Default env — overridden by K8s Deployment env vars
ENV MODULE=frontend
ENV JAVA_OPTS="--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.security=ALL-UNNAMED --add-opens=java.base/sun.security.rsa=ALL-UNNAMED --add-opens=java.base/sun.security.x509=ALL-UNNAMED --add-opens=java.base/sun.security.util=ALL-UNNAMED --add-opens=java.xml/com.sun.org.apache.xerces.internal.jaxp.datatype=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.management/sun.management=ALL-UNNAMED --add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED --add-opens=java.base/jdk.internal.loader=ALL-UNNAMED"

EXPOSE 3030 3035 3036 3080

# Default: start TM (frontend module). Overridden by MODULE env var.
ENTRYPOINT ["/bin/sh", "-lc"]
CMD ["cd /tapdata/apps && if [ -f bin/start.sh ]; then bin/start.sh; elif [ -f start.sh ]; then ./start.sh; else echo 'No start script found'; exit 1; fi"]
