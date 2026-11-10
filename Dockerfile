# syntax=docker/dockerfile:1

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
    && groupadd --system --gid 10001 chatsystem \
    && useradd --system --uid 10001 --gid chatsystem --home-dir /app --shell /usr/sbin/nologin chatsystem
WORKDIR /app
COPY --from=build --chown=chatsystem:chatsystem /workspace/server/build/libs/chat-server.jar ./chat-server.jar
USER chatsystem
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/chat-server.jar"]
