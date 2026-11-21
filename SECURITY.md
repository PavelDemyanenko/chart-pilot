# Security Policy

## Scope

ChartPilot is currently a **development stand**, not a hardened production service:

- OpenSearch Security plugin is disabled; MCP (SSE) and the dashboard WebSocket are unauthenticated.
- PostgreSQL uses default dev credentials (`chartpilot/chartpilot`).
- The LLM API key is passed via environment (`LLM_API_KEY`) and never committed; ML Commons
  masks connector credentials on read.

**Do not expose the stand to public networks.**

## Mitigations already applied

- WebSocket Origin allowlist (CSWSH protection); 128-bit `SecureRandom` session ids with TTL.
- Plugin-side allowlist of local `bridgeUrl` values; `sessionStorage` tab isolation.
- Narrow CSP with explicit `allowedConnectSources`; secrets masked in logs and config dumps.
- Actuator `show-details: when_authorized`; separate management ports.
- Sync DLQ for poison pills, MVCC safety window, advisory lock (single-writer), idempotent bulk.
- K8s `securityContext` (non-root, read-only rootfs, dropped capabilities), resource limits, probes.

## Known debt (tracked, not yet implemented)

- OAuth2/JWT for MCP and one-time wsToken for the WebSocket (Horizon 2, SEC-01 part 2).
- Removing `bridgeUrl` from the session query string (same-origin proxy).
- Redis-backed session registry for replicas > 1 (Horizon 3, ARCH-01).

## Reporting a vulnerability

This repository is private. Please report security issues directly to the repository owner
via GitHub (private vulnerability reporting or a direct message). Do not open public issues
for security problems.