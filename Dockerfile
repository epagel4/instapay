# syntax=docker/dockerfile:1
# Multi-stage build shared by both services: MODULE_DIR selects which adapters module to package.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG MODULE_DIR
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew ":$(echo "$MODULE_DIR" | tr / :):bootJar" --no-daemon -q \
 && cp "$MODULE_DIR"/build/libs/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
USER app
COPY --from=build /app.jar /app/app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
