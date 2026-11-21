# Changelog

## 0.1.0 — 2026-11-21 (MVP)

First public-style packaging of the ChartPilot MVP (private repository).

### Added
- `pg-opensearch-connector`: watermark polling `(updated_at, id)` with MVCC safety window,
  persistent checkpoint, sync DLQ, advisory lock; tombstone-based DELETE replication,
  Flyway migrations (V1 schema+seed, V2 tombstones), alias `pg-orders → pg-orders-v1`
  with legacy reindex migration, Resilience4j Retry + CircuitBreaker.
- `mcp-bridge-server`: Spring AI MCP (SSE) tools `createSession`, `getSessionStatus`,
  `getDashboardContext`, `requestChart`; WebSocket bridge with TTL sessions, Origin allowlist,
  typed tool responses, metrics.
- `osd-plugin/chart_pilot_bridge`: client-only OSD plugin; zero-friction session link,
  `sessionStorage` isolation, reconnect backoff; ECharts assistant action `chart_pilot_chart`
  for inline bar/pie/line charts; time range in `GET_CONTEXT` from `_g` URL.
- `agui-adapter`: AG-UI SSE facade + agent loop, schema-aware prompt, DSL
  normalization, thread history for multi-turn, `CreateVisualizationTool` with
  public URLs and numeric summaries, save-intent gating.
- `mock-llm`: deterministic OpenAI-compatible mock (timeseries/categorical/save branches).
- Minikube deploy (dedicated profile), idempotent AG-UI agent registration Job, localhost
  publishing, demo dashboard provisioning script.
- E2E (JUnit 5 + Playwright + MCP client): sync insert/delete ≤15 s, full MCP→browser→AG-UI
  cycle with DOM chart assertions, invalid session, multi-turn pie→line, save-flow.
- Engineering: root parent POM, SpotBugs + ArchUnit gates, typed DTOs;
  CI workflow (Java 25, `mvn verify`), release workflow (GHCR images).
- Packaging: EN/RU README with logo, MIT license, SECURITY/CONTRIBUTING/CHANGELOG,
  issue templates, docs/tools.md.

### Verified
- 65 service tests green (connector 23, bridge 25, adapter 11, mock 6) + ArchUnit + SpotBugs.
- E2E 6/6 against a real LLM (OpenRouter `openai/gpt-4o-mini`).