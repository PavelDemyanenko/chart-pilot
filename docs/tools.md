# MCP tools reference

Transport: MCP over SSE (`/sse` + `/mcp/message`), served by `mcp-bridge-server`
(Spring AI MCP, Java 25). All responses are structured JSON records (`ToolResponse`).

## createSession

| Parameters | Returns |
|---|---|
| — | `{sessionId, url, status}` |

- `sessionId`: 22-char Base64URL id from 128-bit `SecureRandom`; TTL-bounded registry.
- `url`: clickable browser link `http://<osd>/app/home?sessionId=…&bridgeUrl=ws://<bridge>`.
- `status`: `WAITING_FOR_BROWSER` until the tab connects.
- Session limit: 10000 concurrent; exceeding returns a structured error.

## getSessionStatus

| Parameters | Returns |
|---|---|
| `sessionId` | `{sessionId, status, ttlSeconds?, url}` |

Statuses: `WAITING_FOR_BROWSER`, `CONNECTED`, `DISCONNECTED`. Unknown/expired id →
structured error `NOT_FOUND` (never a stack trace).

## getDashboardContext

| Parameters | Returns |
|---|---|
| `sessionId` | `{status, message, details:{url, title, timeRange}}` |

- Waits for the browser round-trip (15 s timeout) → `COMMAND_FAILED` structured error.
- `timeRange`: active OSD global range parsed from `_g` URL (`{from,to}`), fallback to the
  chat page context; `null` when the current app registers no range.
- Browser not connected → `BROWSER_NOT_CONNECTED` with the session link to open.

## requestChart

| Parameters | Returns |
|---|---|
| `sessionId`, `prompt` (1..4000 chars) | `{status, message, details:{prompt, runStatus}}` |

- Sends `RUN_AG_UI_PROMPT` over the WebSocket; the plugin opens the AG-UI chat window and
  sends the prompt in the existing thread (multi-turn history preserved).
- The reply arrives **after the run completes** (`runStatus: finished`), or as a structured
  error with `runStatus: error|timeout` (plugin waits up to 55 s, bridge timeout 60 s).
- Validation errors (`INVALID_ARGUMENT`) and `BROWSER_NOT_CONNECTED` are structured,
  with a human-readable hint for the agent.
- The chart itself renders in the chat: timeseries via native `graph_timeseries_data`,
  categorical bar/pie/line via the plugin's ECharts action `chart_pilot_chart`;
  saved visualizations via `CreateVisualizationTool` only on explicit save intent.

## Error codes

| Code | Meaning |
|---|---|
| `NOT_FOUND` | sessionId not found or expired |
| `BROWSER_NOT_CONNECTED` | tab not opened yet; response includes the link |
| `COMMAND_FAILED` | browser round-trip failed or timed out |
| `SESSION_LIMIT` | registry capacity reached |
| `INVALID_ARGUMENT` | prompt validation (empty / >4000 chars) |
| `INTERRUPTED` | waiting thread interrupted |