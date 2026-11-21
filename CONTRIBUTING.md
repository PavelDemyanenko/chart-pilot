# Contributing

## Prerequisites

- Java 25 (Eclipse Temurin), Maven 3.9+ (wrappers included per service)
- Docker daemon, Minikube + kubectl, `jq`, `curl`
- Node.js 22 only inside the OSD plugin Docker build

## Build & test

```bash
# all modules: unit + integration (Testcontainers) + ArchUnit + SpotBugs gate
mvn -B -ntp verify -pl '!e2e-tests'

# E2E against a deployed stand
./deploy/scripts/deploy-minikube.sh
(cd e2e-tests && ./mvnw test)                # mock-LLM
(cd e2e-tests && ./mvnw test -DrealLlm=true) # real LLM (LLM_API_KEY in env)
```

Quality gates: SpotBugs `effort=Max threshold=High` fails the build on High findings;
ArchUnit tests enforce package directions and cycle-freedom in every service.

## Rules

- **Design changes**: discuss non-trivial technical/protocol/schema changes in an issue
  before coding; keep the public docs focused on behaviour, not internal decision history.
- **Gates**: a change to MCP tools, the WebSocket protocol or the OSD plugin is done only after
  the E2E suite passes against Minikube.
- **Idempotency**: connector bulk operations use the PostgreSQL `id` as OpenSearch `_id`.
- **Secrets**: never commit keys/tokens; pass via env (`LLM_API_KEY`), keep `.mcp.json`-style
  client configs out of the repo.
- Commit messages: imperative, scoped, e.g. `agui-adapter: ... ; tests N/N`.

## Repository layout

See README.md / README.ru.md. Services are Maven modules of the root parent POM; the OSD plugin
is built inside `deploy/docker/osd-with-plugin.Dockerfile` against a cached OSD bootstrap image.