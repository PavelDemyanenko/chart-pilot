<p align="center">
  <img src="docs/assets/logo-128.png" width="128" height="120" alt="ChartPilot logo">
</p>

<h1 align="center">ChartPilot</h1>

<p align="center">
  Prompt-driven charts over your <b>PostgreSQL</b> data, drawn in the built-in AG-UI chat of
  <b>OpenSearch Dashboards</b> from your terminal (Qwen Code, OpenCode, any MCP client):<br>
  <i>"plot order totals by region"</i> — and the chart appears in the open browser tab.
</p>

<p align="center">
  <a href="https://github.com/PavelDemyanenko/chart-pilot/actions/workflows/ci.yml">CI (GitHub Actions)</a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT license"></a>
  <img src="https://img.shields.io/badge/Java-25-orange.svg" alt="Java 25">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1-6db33f.svg" alt="Spring Boot 4.1">
</p>

<p align="center"><a href="README.ru.md">Русская версия</a></p>

---

## Why

Dashboards are great until you need *one more chart, right now*. ChartPilot lets an AI agent
in your terminal drive the visualization loop end to end:

- An **MCP server** (`mcp-bridge-server`) exposes four tools: open a browser session, check its
  status, read what the user is looking at, and send a chart prompt into the chat.
- A **client-only OpenSearch Dashboards plugin** (`chart_pilot_bridge`) binds the browser tab to
  the terminal session (zero-friction link, `sessionStorage` isolation, reconnect backoff) and
  drives the built-in AG-UI chat — no custom UI, no page reloads.
- An **AG-UI adapter** (`agui-adapter`) runs the agent loop: schema-aware system prompt,
  OpenSearch tools, and inline charts. Time series render through the native
  `graph_timeseries_data` tool; categorical bar/pie/line charts render through a plugin-registered
  assistant action backed by **ECharts**.
- A **connector** (`pg-opensearch-connector`) keeps OpenSearch in sync with PostgreSQL:
  watermark polling with an MVCC safety window, tombstone-based DELETE replication, Flyway
  migrations, index aliasing, DLQ, and Resilience4j retry/circuit breaker.

```text
Terminal (MCP client)                    Browser (OpenSearch Dashboards 3.10)
  │  MCP SSE (/sse, /mcp/message)          │  WebSocket (/ws/dashboard?sessionId=…)
  ▼                                        ▼
mcp-bridge-server ── commands ──▶ chart_pilot_bridge plugin ──▶ AG-UI chat
  createSession / getSessionStatus /            │  RunAgentInput → SSE
  getDashboardContext / requestChart            ▼
                                         agui-adapter (agent loop)
                                           │  OpenSearch REST tools + LLM connector
PostgreSQL 16 ── pg-opensearch-connector ──▶ OpenSearch (alias pg-orders → pg-orders-v1)
```

> OpenSearch 3.10.0's REST engine has no streaming channels, so the native `AG_UI` agent
> (`_execute/stream`) cannot run over REST; the AG-UI protocol is served by `agui-adapter`
> ML Commons agent/model registration is kept for compatibility.

## Tools

| Tool | Parameters | Returns |
|---|---|---|
| `createSession` | — | `{sessionId, url, status}`: short readable id + clickable browser link |
| `getSessionStatus` | `sessionId` | `WAITING_FOR_BROWSER` / `CONNECTED` / `DISCONNECTED` + TTL info |
| `getDashboardContext` | `sessionId` | current OSD URL, page title, active time range (from `_g`) |
| `requestChart` | `sessionId`, `prompt` (1..4000 chars) | delivery status; the chart + numeric answer appear in the chat |

Details and error codes: [docs/tools.md](docs/tools.md).

## Quick start

You need: **Docker**, **Minikube** + **kubectl**, `jq`, `curl`; Java 25 / Maven only if you build
without Docker. ~7 GB RAM for the cluster, ~20 GB disk.

```bash
./deploy/scripts/deploy-minikube.sh
```

The script creates a **dedicated** Minikube profile `chart-pilot` (other clusters untouched),
builds and loads all images, applies `deploy/k8s/`, registers the AG-UI agent (idempotent Job),
publishes services on localhost, verifies 500 synced documents and prints the URLs:

| Service | Address |
|---|---|
| OpenSearch Dashboards | http://127.0.0.1:30560 |
| MCP Bridge (SSE) | http://127.0.0.1:30880/sse |
| OpenSearch API | http://127.0.0.1:30920 |
| PostgreSQL | jdbc:postgresql://127.0.0.1:30432/chartpilot (chartpilot/chartpilot) |

`E2E_MODE=true` (default) deploys a deterministic `mock-llm` instead of a paid LLM.
For a real model: `E2E_MODE=false LLM_API_KEY=… ./deploy/scripts/deploy-minikube.sh`.

### Connect your MCP client

**Qwen Code:**

```bash
qwen mcp add --transport sse chart-pilot http://127.0.0.1:30880/sse --timeout 60000
```

**OpenCode** (`~/.config/opencode/opencode.json`):

```json
{
  "mcp": {
    "chart-pilot": {
      "type": "remote",
      "url": "http://127.0.0.1:30880/sse",
      "enabled": true,
      "timeout": 60000
    }
  }
}
```

### Example dialogue

1. *"Create a ChartPilot session"* → `createSession()` returns a link like
   `http://127.0.0.1:30560/app/home?sessionId=…&bridgeUrl=ws://127.0.0.1:30880`.
2. Open the link — a toast confirms *"ChartPilot MCP connected"*.
3. *"Plot a bar chart of order totals by category from pg-orders"* → `requestChart()`;
   the AG-UI chat panel opens automatically and an inline ECharts chart appears with numbers.
4. *"Now group by region"* / *"switch to a line chart"* — multi-turn, history preserved.
5. *"Save this visualization"* → a saved OSD visualization plus a public link and numbers.

Real screenshots of these flows: [docs/examples.md](docs/examples.md).

## Tests

```bash
# whole monorepo: unit + integration + ArchUnit + SpotBugs gate
mvn -B -ntp verify -pl '!e2e-tests'
# per module (Testcontainers: PostgreSQL 16 + OpenSearch 3.10.0)
(cd services/pg-opensearch-connector && ./mvnw test)   # 23 tests
(cd services/mcp-bridge-server && ./mvnw test)         # 25 tests
(cd services/agui-adapter && ./mvnw test)              # 11 tests
(cd services/mock-llm && ./mvnw test)                  # 6 tests

# E2E against a deployed stand (JUnit 5 + Playwright + MCP client)
(cd e2e-tests && ./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium")
(cd e2e-tests && ./mvnw test)                 # mock-LLM stand
(cd e2e-tests && ./mvnw test -DrealLlm=true)  # real-LLM stand (OpenRouter etc.)
```

E2E scenarios: PG→OpenSearch sync ≤15 s (insert **and** delete); full MCP→browser→AG-UI cycle
with DOM assertions (chat opened, prompt/answer present, chart canvas visible, no console
errors); invalid `sessionId` → structured error; multi-turn pie→line in one session;
save-flow with public visualize URL and active time range in `getDashboardContext`.

## Security

This is a **dev stand**: OpenSearch Security disabled, MCP/WS unauthenticated, default PostgreSQL
passwords. Hardening mitigations are applied (Origin allowlist, 128-bit session ids, local `bridgeUrl`
allowlist, narrow CSP, secret masking, DLQ, MVCC safety window).
**Do not expose the stand to public networks.** Production hardening (OAuth2/JWT, one-time
wsToken, Redis-backed sessions, CDC) is tracked as Horizon 2/3 debt. See [SECURITY.md](SECURITY.md).

## Repository layout

```
services/pg-opensearch-connector/   PostgreSQL → OpenSearch sync (Flyway, tombstones, alias)
services/mcp-bridge-server/         MCP server + WebSocket bridge
services/agui-adapter/              AG-UI SSE facade + agent loop
services/mock-llm/                  OpenAI-compatible deterministic mock for E2E
osd-plugin/chart_pilot_bridge/      client-only OSD plugin (TypeScript, ECharts action)
deploy/docker/                      Dockerfiles (incl. OSD-with-plugin build)
deploy/k8s/                         Minikube manifests
deploy/scripts/                     deploy-minikube.sh, register-agui-agent.sh, helpers
e2e-tests/                          JUnit 5 + Playwright E2E
docs/                               assets, examples, tools reference
```

## License

[MIT](LICENSE) © Pavel Demyanenko