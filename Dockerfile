# syntax=docker/dockerfile:1

# The published image lags the wrapper; ./gradlew downloads and runs Gradle 9.9.1.
FROM gradle:9.8.2-jdk27 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle gradlew gradlew.bat ./
COPY gradle ./gradle
COPY client-sdk/build.gradle ./client-sdk/build.gradle
COPY desktop-shared ./desktop-shared
COPY desktop-user ./desktop-user
COPY desktop-admin ./desktop-admin
COPY api ./api
COPY server/build.gradle ./server/build.gradle
COPY server/src ./server/src
RUN ./gradlew --no-daemon :server:bootJar

FROM eclipse-temurin:27-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 nexus \
    && useradd --system --uid 10001 --gid nexus --home-dir /app --shell /usr/sbin/nologin nexus
WORKDIR /app
COPY --from=build --chown=nexus:nexus /workspace/server/build/libs/nexus-server.jar ./nexus-server.jar
USER nexus
EXPOSE 8080
# Heap from the container limit, fail fast on OOM, keep a rolling GC log. JFR is opt-in:
#   docker run -e JAVA_TOOL_OPTIONS='-XX:StartFlightRecording=filename=/tmp/app.jfr,settings=profile' ...
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=70", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-XX:+HeapDumpOnOutOfMemoryError", \
    "-XX:HeapDumpPath=/tmp", \
    "-Xlog:gc*:file=/tmp/gc.log:time,uptime,level,tags:filecount=3,filesize=10M", \
    "-jar", "/app/nexus-server.jar"]
