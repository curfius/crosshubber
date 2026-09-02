# Portal Java backend + Angular UI — multi-stage
# Stage 1: Build Angular UI
FROM node:22-alpine AS ui-build
WORKDIR /app/ui
COPY ui/package.json ui/package-lock.json ./
RUN npm ci
COPY ui/ ./
# Build Angular — output to dist/portal-ui/browser
RUN npm run build

# Stage 2: Build Java server (Maven)
FROM maven:3.9-eclipse-temurin-21 AS server-build
WORKDIR /app
# Copy pom and checkstyle config, cache dependencies first (fast rebuilds)
COPY server/pom.xml ./pom.xml
COPY server/checkstyle.xml ./checkstyle.xml
COPY server/checkstyle-header.txt ./checkstyle-header.txt
RUN mvn -B -q -Dcheckstyle.skip=true -Dspotless.check.skip=true dependency:go-offline
# Copy source + UI build into Spring static resources
COPY server/src ./src
COPY --from=ui-build /app/ui/dist/portal-ui/browser ./src/main/resources/static/
RUN mvn -B -DskipTests -Dcheckstyle.skip=true -Dspotless.check.skip=true package

# Stage 3: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
# curl for healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=server-build /app/target/*.jar app.jar
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=30s \
  CMD curl -f http://127.0.0.1:3000/healthz || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
