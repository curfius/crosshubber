# Staffing module server — single-stage Maven build (no UI workspace yet)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./pom.xml
COPY checkstyle.xml ./checkstyle.xml
COPY checkstyle-header.txt ./checkstyle-header.txt
RUN mvn -B -q -Dcheckstyle.skip=true -Dspotless.check.skip=true dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests -Dcheckstyle.skip=true -Dspotless.check.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8091
HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=30s \
  CMD curl -f http://127.0.0.1:8091/healthz || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
