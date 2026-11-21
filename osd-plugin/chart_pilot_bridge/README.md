# chart_pilot_bridge — client-only плагин OpenSearch Dashboards

Мост между `mcp-bridge-server` (ChartPilot MCP) и встроенным AG-UI чатом OpenSearch Dashboards 3.10.0.

## Поведение

* `server: false`, `ui: true`, `requiredPlugins: ["chat"]`.
* При старте читает `?sessionId=...` и `?bridgeUrl=...` из URL, сохраняет в `sessionStorage`
  (`chart_pilot_session_id`, `chart_pilot_bridge_url`) и переиспользует при навигации внутри OSD.
  Без `sessionId` WebSocket не открывается — обычная работа OSD не затрагивается.
* WebSocket: `<bridgeUrl>/ws/dashboard?sessionId=<id>`, авто-perеподключение с экспоненциальным
  backoff 1с → 2с → 4с → … → 10с. Toast при подключении и при обрыве.
* `GET_CONTEXT` → `{status:"ok", details:{url, title}}` текущей страницы.
* `RUN_AG_UI_PROMPT` → `chatService.openWindow()` + `chatService.sendMessageWithWindow(prompt, [])`
  (тред и история сохраняются — multi-turn). Все исключения перехватываются и возвращаются мосту
  как `{status:"error", message}`.
* Fallback без `bridgeUrl`: `ws://<window.location.hostname>:8080`.

## Протокол WebSocket

Сервер → браузер:

```json
{ "correlationId": "uuid", "action": "RUN_AG_UI_PROMPT | GET_CONTEXT", "payload": { "prompt": "..." } }
```

Браузер → сервер:

```json
{ "correlationId": "uuid", "result": { "status": "ok | error", "message": "...", "details": {} } }
```

## Сборка

Плагин собирается только внутри дерева исходников OSD (см. `deploy/docker/osd-with-plugin.Dockerfile`):

```bash
# из корня репозитория
docker build -t osd-chart-pilot:local -f deploy/docker/osd-with-plugin.Dockerfile .
```

Этапы: загрузка tarball исходников OSD 3.10.0 → `yarn osd bootstrap` (Node 22.23.4) → копирование плагина в
`plugins/chart_pilot_bridge` → `tsc --noEmit` → `yarn plugin-helpers build` → установка zip в
официальный образ `opensearchproject/opensearch-dashboards:3.10.0`.

## Конфигурация OSD (`deploy/docker/opensearch_dashboards.yml`)

```yaml
chat.enabled: true
chat.agUiUrl: "${AG_UI_URL}"        # http://opensearch:9200/_plugins/_ml/agents/<id>/_execute/stream
chat.mlCommonsAgentId: "${AGENT_ID}"
csp.rules: "... connect-src 'self' ws: wss:"
```

`AG_UI_URL` и `AGENT_ID` передаются контейнеру через env (ConfigMap `osd-agui-config`, Фаза 4).