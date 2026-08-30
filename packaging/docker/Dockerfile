# syntax=docker/dockerfile:1.7
#
# One Dockerfile, two images, one jar.
#
#   docker build -f packaging/docker/Dockerfile --target runner -t concentus-runner .
#   docker build -f packaging/docker/Dockerfile --target hub    -t concentus-hub    .
#
# The build context is the REPOSITORY ROOT (the trailing dot), not this folder: the jar is built
# from the whole tree — the frontend is compiled first and copied into it as static resources,
# which is what puts the UI, /api and /ws on one origin. The root .dockerignore keeps node_modules,
# target/, the desktop payload and .git out of what gets sent.
#
# Why the jar is built here rather than copied in from a release: the PostgreSQL binaries inside
# it are chosen by the OS that runs Maven (an OS-activated profile in apps/backend/pom.xml), so a
# jar built on Windows or macOS carries binaries a Linux container cannot run. Building inside a
# Linux stage is the one way to be sure the jar and the image agree. The Linux profile picks
# amd64 binaries only; see the note on the hub target about other architectures.
#
# Both final images run as a user that is not root, keep their state under /data, and carry no
# credential of any kind. A runner's Claude login arrives on its environment at `docker run`,
# from whoever operates it — never baked in, for the reason the README's Runners section gives.

# Pinned, and the archives are checked against these sums before anything is unpacked. Bump a
# version and its sums together — nodejs.org publishes SHASUMS256.txt beside each release,
# archive.apache.org a .sha512 beside each tarball. Declared before the first FROM so the build
# stage and the runner stage install the same Node from one definition.
ARG NODE_VERSION=22.23.2
ARG NODE_SHA256_X64=d60acfe00a2932254bb0ad20e01b0d74397a0875595de719654b214f4b03f307
ARG NODE_SHA256_ARM64=fff4078c5def658577f92c88db7db3bc0072924bfb93fe52c1e744a54e94abb8
ARG MAVEN_VERSION=3.9.11
ARG MAVEN_SHA512=bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978
# Must match "packageManager" in the root package.json, or corepack refuses to activate it.
ARG PNPM_VERSION=9.15.0

# ---------------------------------------------------------------------------- build
# Ubuntu-based, so apt works for the few tools Node and Maven need around them.
FROM eclipse-temurin:25-jdk AS build
ARG NODE_VERSION
ARG NODE_SHA256_X64
ARG NODE_SHA256_ARM64
ARG MAVEN_VERSION
ARG MAVEN_SHA512
ARG PNPM_VERSION

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl xz-utils git \
 && rm -rf /var/lib/apt/lists/*

# Node from nodejs.org's own tarball rather than a distribution package or a setup script piped
# into a shell: the version is exact, the checksum is verified, and one recipe serves both
# architectures.
RUN set -eu; \
    case "$(dpkg --print-architecture)" in \
      amd64) node_arch=x64;   sha="$NODE_SHA256_X64" ;; \
      arm64) node_arch=arm64; sha="$NODE_SHA256_ARM64" ;; \
      *) echo "unsupported architecture: $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac; \
    curl -fsSLo /tmp/node.tar.xz "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${node_arch}.tar.xz"; \
    echo "${sha}  /tmp/node.tar.xz" | sha256sum -c -; \
    tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 --no-same-owner \
        --exclude=CHANGELOG.md --exclude=LICENSE --exclude=README.md; \
    rm /tmp/node.tar.xz; \
    node --version
RUN corepack enable && corepack prepare "pnpm@${PNPM_VERSION}" --activate

# Maven from Apache's archive, not apt's: Ubuntu's package lags by years and drags in its own
# JDK, which is not the one this build is about.
ENV PATH="/opt/maven/bin:${PATH}"
RUN set -eu; \
    curl -fsSLo /tmp/maven.tar.gz "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"; \
    echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c -; \
    mkdir -p /opt/maven; \
    tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1; \
    rm /tmp/maven.tar.gz; \
    mvn --version

WORKDIR /src

# The manifests first, so a source change does not throw away the installed node_modules layer.
#
# The whole workspace, not `--filter frontend`, although nothing of the desktop is built here:
# the frontend's `tsc -b` also type-checks its Playwright files, and the @types/node those need
# is declared by apps/desktop alone — it reaches the frontend by hoisting, so a frontend-only
# install fails the build with "Cannot find name 'process'". Installing the desktop would
# normally download Electron in its postinstall; the variable below is that script's own
# opt-out, so the package installs and the hundred-megabyte binary is never fetched.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/frontend/package.json apps/frontend/
COPY apps/desktop/package.json apps/desktop/
ENV ELECTRON_SKIP_BINARY_DOWNLOAD=1
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

COPY . .

# Order matters, as in release.yml: the frontend is copied into the jar as static resources, so it
# has to exist before Maven packages.
RUN pnpm --filter frontend build

# Tests are CI's job, not the image's — the commit being built has already been through them, and
# the store tests need a PostgreSQL that will not start as root. The ~/.m2 cache mount keeps the
# dependency download out of every rebuild.
#
# What is NOT here: pgvector. The release builds it against PostgreSQL's own headers in a CI action
# and stages it into the jar's resources; this image does not repeat that, so a hub from it ranks
# tool search and knowledge retrieval the way the jar does without the extension, and says so in
# its log at startup. A runner never opens a database and never misses it.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -f apps/backend/pom.xml clean package -DskipTests

# ---------------------------------------------------------------------------- base
# What the two images share: a JRE, git, a user, the jar, and /data.
FROM eclipse-temurin:25-jre AS base

# git is what Repository nodes clone with and what the merge step commits with; ca-certificates
# is what lets it — and every outbound call the jar makes — trust anybody.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates git \
 && rm -rf /var/lib/apt/lists/*

# Not root, and not uid 1000 either: the Ubuntu base already has an `ubuntu` user there, and a
# uid that means one thing inside the image and another on the host is how bind mounts end up
# unwritable. A named volume (what docker-compose.yml uses) takes the ownership set below on its
# first use; a bind mount needs `chown -R 10001` on the host side.
RUN useradd --create-home --home-dir /home/concentus --uid 10001 --shell /bin/bash concentus \
 && mkdir -p /app /data \
 && chown concentus:concentus /data

COPY --from=build --chown=concentus:concentus /src/apps/backend/target/concentus-backend.jar /app/concentus-backend.jar

# Blank means the version chip in the header is simply not shown; the release workflow passes
# the tag's version so an image knows what it is.
ARG CONCENTUS_VERSION=
ENV CONCENTUS_APP_VERSION=${CONCENTUS_VERSION}
# The source label is what makes ghcr.io show the package on the repository and inherit its
# visibility, rather than leaving it private under the account.
LABEL org.opencontainers.image.source="https://github.com/Gergilcan/concentus" \
      org.opencontainers.image.licenses="PolyForm-Noncommercial-1.0.0" \
      org.opencontainers.image.version="${CONCENTUS_VERSION}"

VOLUME /data
USER concentus
WORKDIR /home/concentus

# ---------------------------------------------------------------------------- hub
# The control plane: flows, runs, accounts, credentials, the UI. Same jar, started normally.
#
#   docker run -d -p 8080:8080 -v concentus-hub:/data \
#     -e CONCENTUS_SECRET_KEY=$(openssl rand -base64 32) \
#     concentus-hub
#
# CONCENTUS_SECRET_KEY has to be set, and has to stay the same for the life of the data: it is
# the key every stored credential and secret setting is sealed with. Without it they are written
# as typed, and anyone who can read the database can read them; with a DIFFERENT one on the next
# start, everything sealed before shows as locked until it is entered again. Mint it once
# (`openssl rand -base64 32` — 32 random bytes, base64) and keep it with the rest of the
# deployment's secrets, not in the image and not in the shell history of the line above.
#
# The database: PERSIST_DB_URL / PERSIST_DB_USER / PERSIST_DB_PASSWORD point the hub at a
# PostgreSQL of yours, which needs a Team or Enterprise license — without one the hub says so and
# starts on its embedded PostgreSQL under /data instead, which is perfectly good for one hub with
# a volume. That embedded server is amd64: on an arm64 host (Apple Silicon, Graviton) build with
# `--platform linux/amd64`, or point the hub at an external database. The runner image has no
# such limit — it never starts a database.
#
# The first account: open the port in a browser and the setup screen asks for it. Where nobody
# will, CONCENTUS_ADMIN_EMAIL and CONCENTUS_ADMIN_PASSWORD (both, or neither) provision it.
#
# Nothing here is loopback-only, unlike the desktop profile: the hub is reached over a network by
# design, and what stands in front of the API is the sign-in, not the socket. Put TLS in front of
# it — runners carry their token as a bearer on a WebSocket, and clone tokens travel over that
# socket.
FROM base AS hub
ENV APP_DATA_DIR=/data \
    PORT=8080
EXPOSE 8080
# --enable-native-access silences the JDK's warning about JNA's native calls; it changes nothing
# else. --server.address undoes nothing — the default already binds every interface — and is here
# so that a reader does not have to know that.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/concentus-backend.jar", "--server.address=0.0.0.0"]

# ---------------------------------------------------------------------------- runner
# The execution half on its own: connects OUT to a hub, registers with a token minted there, and
# runs the `claude` CLI turns the hub hands it. No port, no database, no Spring — `runner` as the
# first argument means the jar never starts them.
#
#   docker run -d -v concentus-runner:/data \
#     -e CONCENTUS_RUNNER_URL=https://hub.example.com \
#     -e CONCENTUS_RUNNER_TOKEN=crn_… \
#     -e CLAUDE_CODE_OAUTH_TOKEN=…  \
#     concentus-runner
#
# No login is baked in, and none ever should be. Anthropic's terms do not allow a subscription's
# credentials to be routed through a third party, which is what a hub holding them would be. The
# rule that follows: the credential stays where its owner runs the process. Whoever operates this
# container puts THEIR auth on its environment — CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token`
# on a machine where they are logged in, or ANTHROPIC_API_KEY — and the hub only ever learns
# which kind it was. A `claude /login` done inside the container works too, and survives restarts
# if /home/concentus/.claude is a volume.
#
# Everything else comes from the environment as well, so there is no CMD to override:
#   CONCENTUS_RUNNER_NAME       a label for this process; the roster shows the registered name
#   LOCAL_CONTEXT_ROOTS         the folders a flow's context folders may point into — mount them,
#                               then name them here; empty rejects every one, as on a hub
#   EXECUTION_MAX_PROCESSES     CLI processes at once on this runner (4 when unset)
#   CLAUDE_COMMAND              the CLI, when it is not the one installed below
FROM base AS runner
ARG NODE_VERSION
ARG NODE_SHA256_X64
ARG NODE_SHA256_ARM64

USER root
# The same Node as the build, for the same reasons, plus the CLI the runner exists to run. The
# CLI is installed globally and pinned to nothing on purpose: a rebuild picks up the current one,
# which is what a person's own machine would have.
RUN set -eu; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl xz-utils; \
    rm -rf /var/lib/apt/lists/*; \
    case "$(dpkg --print-architecture)" in \
      amd64) node_arch=x64;   sha="$NODE_SHA256_X64" ;; \
      arm64) node_arch=arm64; sha="$NODE_SHA256_ARM64" ;; \
      *) echo "unsupported architecture: $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac; \
    curl -fsSLo /tmp/node.tar.xz "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${node_arch}.tar.xz"; \
    echo "${sha}  /tmp/node.tar.xz" | sha256sum -c -; \
    tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 --no-same-owner \
        --exclude=CHANGELOG.md --exclude=LICENSE --exclude=README.md; \
    rm /tmp/node.tar.xz; \
    npm install -g @anthropic-ai/claude-code; \
    claude --version
USER concentus

ENV CONCENTUS_RUNNER_DATA_DIR=/data \
    HOME=/home/concentus
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/concentus-backend.jar", "runner"]
