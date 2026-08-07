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
#
# tesseract-ocr is the one native dependency in the image. PDF, Word and Excel attachments are
# parsed in pure Java (PDFBox/POI), but reading an image attachment or a scanned PDF needs the
# native library plus its language data — Spanish and English here, matching
# integration.attachments.ocr-languages. Everything still starts without these: the OCR extractor
# reports itself unavailable and image attachments are skipped, so removing them to slim the image
# costs a feature rather than breaking the app. Set OCR_ENABLED=false if you do.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates git \
      tesseract-ocr tesseract-ocr-spa tesseract-ocr-eng \
 && curl -fsSL https://deb.nodesource.com/setup_24.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && npm install -g @anthropic-ai/claude-code \
 && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# A glob, not the artifact's name. The name is decided by the pom — artifactId, version, or a
# <finalName> — and hard-coding it means a version bump breaks the image build with a "not found"
# that points at the Dockerfile rather than at the pom. Exactly one jar is produced here; the
# repackaged original is left as .jar.original, which this does not match.
COPY --from=build /src/target/*.jar /app/app.jar

# Run locally on the subscription login (no API key). See docker-compose for how auth is supplied.
ENV ANTHROPIC_AUTH_MODE=local \
    CLAUDE_COMMAND=claude \
    APP_DATA_DIR=/data
VOLUME /data
EXPOSE 8080
# /app/app.jar, not /app.jar. The COPY above lands it under WORKDIR, and the two differ by one
# slash — which fails at container start rather than at build time, so it survives a green build.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
