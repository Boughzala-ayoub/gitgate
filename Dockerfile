# Stage 1 — dashboard. Split from the Maven build so that changing Java source doesn't
# re-run `npm ci`, and changing frontend source doesn't re-resolve Maven dependencies.
# Each COPY is ordered cheapest-to-change-last so Docker's layer cache does real work.
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build
# vite writes to ../src/main/resources/static → /app/src/main/resources/static

# Stage 2 — server.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=frontend /app/src/main/resources/static ./src/main/resources/static
# The dashboard is already built and copied in, so skip the frontend plugin here.
RUN mvn -B -DskipTests -Dskip.frontend=true package

FROM eclipse-temurin:21-jre
WORKDIR /app

# Everything persistent (H2 database, canonical repos, materialized views) lives on the volume.
ENV GITGATE_DATA_DIR=/data
VOLUME /data
EXPOSE 8080

RUN useradd --system --uid 1001 gitgate && mkdir -p /data && chown gitgate /data
USER gitgate

COPY --from=build /src/target/gitgate-*.jar /app/gitgate.jar
ENTRYPOINT ["java", "-jar", "/app/gitgate.jar"]
