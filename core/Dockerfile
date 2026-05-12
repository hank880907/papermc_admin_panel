# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY shared shared
COPY core core
RUN ./gradlew :core:shadowJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir -p /app/data
COPY --from=build /workspace/core/build/libs/*-all.jar /app/admin-panel-core.jar
ENV ADMINPANEL_DB_PATH=/app/data/admin-panel.db
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/admin-panel-core.jar"]
