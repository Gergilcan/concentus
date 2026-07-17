# syntax=docker/dockerfile:1

# ---------- build the Spring Boot jar ----------
# If the maven:...-25 tag is ever unavailable, pin to -24 and set <java.version>24</java.version>.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY apps/backend/pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY apps/backend/src ./src
RUN mvn -q -DskipTests package

# ---------- runtime: JRE + Node + the claude CLI ----------
FROM eclipse-temurin:25-jre
# Claude Code is a Node CLI; git lets delegated agents work with repos.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates git \
 && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && npm install -g @anthropic-ai/claude-code \
 && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /src/target/concentus-backend-1.0.0.jar app.jar

# Run locally on the subscription login (no API key). See docker-compose for how auth is supplied.
ENV ANTHROPIC_AUTH_MODE=local \
    CLAUDE_COMMAND=claude \
    APP_DATA_DIR=/data
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
