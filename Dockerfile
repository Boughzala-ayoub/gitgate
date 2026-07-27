# Build: Maven drives the frontend build too (frontend-maven-plugin fetches its own Node),
# so this stage needs nothing but a JDK and the sources.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Dependencies first, so edits to sources don't re-download the world.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY frontend/package.json frontend/package-lock.json ./frontend/
COPY frontend ./frontend
COPY src ./src
RUN mvn -B -DskipTests package

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
