# Сборка из КОРНЯ репозитория (parent POM, ADR-0012):
#   docker build --network=host -t mcp-bridge-server:local -f services/mcp-bridge-server/Dockerfile .
FROM maven:3.10-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./pom.xml
COPY services/mcp-bridge-server/pom.xml ./services/mcp-bridge-server/pom.xml
WORKDIR /build/services/mcp-bridge-server
RUN --mount=type=cache,target=/root/.m2/repository mvn -q -DskipTests dependency:go-offline
COPY services/mcp-bridge-server/src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -q -DskipTests package

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
RUN groupadd -r -g 1001 app && useradd -r -u 1001 -g app app
COPY --from=build /build/services/mcp-bridge-server/target/mcp-bridge-server-*.jar app.jar
USER 1001:1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]