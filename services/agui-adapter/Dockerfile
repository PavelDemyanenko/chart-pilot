# Сборка из КОРНЯ репозитория (parent POM, ADR-0012):
#   docker build --network=host -t agui-adapter:local -f services/agui-adapter/Dockerfile .
FROM maven:3.10-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./pom.xml
COPY services/agui-adapter/pom.xml ./services/agui-adapter/pom.xml
WORKDIR /build/services/agui-adapter
RUN --mount=type=cache,target=/root/.m2/repository mvn -q -DskipTests dependency:go-offline
COPY services/agui-adapter/src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -q -DskipTests package

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
RUN groupadd -r -g 1001 app && useradd -r -u 1001 -g app app
COPY --from=build /build/services/agui-adapter/target/agui-adapter-*.jar app.jar
USER 1001:1001
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]