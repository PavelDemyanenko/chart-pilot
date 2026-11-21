# Сборка из КОРНЯ репозитория (parent POM, ADR-0012):
#   docker build --network=host -t pg-opensearch-connector:local -f services/pg-opensearch-connector/Dockerfile .
FROM maven:3.10-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./pom.xml
COPY services/pg-opensearch-connector/pom.xml ./services/pg-opensearch-connector/pom.xml
WORKDIR /build/services/pg-opensearch-connector
RUN --mount=type=cache,target=/root/.m2/repository mvn -q -DskipTests dependency:go-offline
COPY services/pg-opensearch-connector/src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -q -DskipTests package

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
RUN groupadd -r -g 1001 app && useradd -r -u 1001 -g app app
COPY --from=build /build/services/pg-opensearch-connector/target/pg-opensearch-connector-*.jar app.jar
USER 1001:1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]