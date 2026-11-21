<p align="center">
  <img src="docs/assets/logo-128.png" width="128" height="120" alt="Логотип ChartPilot">
</p>

<h1 align="center">ChartPilot</h1>

<p align="center">
  Графики по данным PostgreSQL из вашего терминала (Qwen Code, OpenCode, любой MCP-клиент)<br>
  во встроенном AG-UI чате OpenSearch Dashboards: <b>«построй график суммы заказов по регионам»</b> —<br>
  и график появляется в открытой вкладке браузера.
</p>

<p align="center">
  <a href="https://github.com/PavelDemyanenko/chart-pilot/actions/workflows/ci.yml"><img src="https://github.com/PavelDemyanenko/chart-pilot/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT license"></a>
  <img src="https://img.shields.io/badge/Java-25-orange.svg" alt="Java 25">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1-6db33f.svg" alt="Spring Boot 4.1">
</p>

<p align="center"><a href="README.md">English version</a></p>

---

## Архитектура

```
Терминал (MCP-клиент)                     Браузер (OpenSearch Dashboards 3.10)
  │  MCP SSE (/sse, /mcp/message)           │  WebSocket (/ws/dashboard?sessionId=…)
  ▼                                         ▼
mcp-bridge-server (Java 25, Spring Boot 4.1.2, Spring AI 2.0.2)
  createSession / getSessionStatus / getDashboardContext / requestChart
  DashboardSessionRegistry (TTL, SecureRandom-сессии) + CompletableFuture по correlationId
                                            │  RUN_AG_UI_PROMPT / GET_CONTEXT
                                            ▼
                              osd-plugin/chart_pilot_bridge (client-only, requiredPlugins: [chat])
                                chatService.openWindow() + sendMessageWithWindow()
                                + chart_pilot_chart (ECharts): inline bar/pie/line в чате
                                            │  AG-UI RunAgentInput → SSE (chat.agUiUrl)
                                            ▼
                              agui-adapter: agent-loop, AG-UI события
                                LLM через endpoint коннектора OpenSearch + Backend tools
                                (ListIndexTool / IndexMappingTool / SearchIndexTool → OS REST;
                                 CreateVisualizationTool → OSD saved-objects API)
                                            ▲  Bulk API (_id = order.id)
PostgreSQL 16 ── pg-opensearch-connector ───┘
  orders (500 seed-записей)   polling по watermark (updated_at, id) + MVCC safety window,
                              DELETE через tombstones (orders_deleted + trigger),
                              persistent checkpoint, DLQ, advisory lock (Active/Standby),
                              Flyway-миграции, alias pg-orders → pg-orders-v1,
                              Resilience4j Retry + CircuitBreaker на bulk
```

> В OpenSearch 3.10.0 REST-движок не поддерживает streaming-каналы, поэтому нативный
> `AG_UI`-агент (`_execute/stream`) неисполним через REST; AG-UI-протокол обслуживает
> `agui-adapter`. Регистрация AG_UI-агента и модели в ML Commons сохраняется.

## Требования

- **Java 25** (Eclipse Temurin), Maven 3.9+ (в репозитории есть `mvnw`)
- **Docker** (демон запущен), **Minikube** + **kubectl**, `jq`, `curl`
- **Node.js 22** — только для сборки образа OSD с плагином (внутри Docker)
- RAM: ~7 GB под кластер Minikube + ~2 GB под сборку образов; диск ~20 GB
- WSL2: сборка образов идёт с `--network=host` (bridge-сеть docker0 вешает крупные загрузки)

## Быстрый старт (одна команда)

```bash
./deploy/scripts/deploy-minikube.sh
```

Скрипт:
1. поднимает **выделенный** профиль Minikube `chart-pilot` (containerd, 7 GB / 4 CPU) — другие кластеры не затрагиваются;
2. собирает образы `pg-opensearch-connector`, `mcp-bridge-server`, `agui-adapter`, `mock-llm`,
   `osd-chart-pilot` (bootstrap OSD кешируется в `chartpilot/osd-bootstrap:3.10.0`,
   повторные сборки ~2–3 мин);
3. загружает образы в кластер (`minikube image load`), применяет манифесты `deploy/k8s/`;
4. регистрирует AG-UI агента (идемпотентный Init Job `register-agui-agent`);
5. публикует сервисы на localhost через socat-сайдкары (обход немаршрутизируемого IP узла в WSL2);
6. проверяет `pg-orders` (500 документов) и печатает URL + команду подключения.

| Сервис | Адрес |
|---|---|
| OpenSearch Dashboards | http://127.0.0.1:30560 |
| MCP Bridge (SSE) | http://127.0.0.1:30880/sse |
| OpenSearch API | http://127.0.0.1:30920 |
| PostgreSQL | jdbc:postgresql://127.0.0.1:30432/chartpilot (chartpilot/chartpilot) |

`E2E_MODE=true` (по умолчанию) разворачивает детерминированный `mock-llm` вместо платного LLM.
Для реального LLM: `E2E_MODE=false LLM_API_KEY=… ./deploy/scripts/deploy-minikube.sh`.

## Подключение MCP-клиента

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

### Пример диалога

1. *«Создай сессию ChartPilot»* → `createSession()` вернёт ссылку вида
   `http://127.0.0.1:30560/app/home?sessionId=…&bridgeUrl=ws://127.0.0.1:30880`.
2. Откройте ссылку в браузере — появится toast «ChartPilot MCP подключён».
3. *«Построй столбчатый график суммы заказов по категориям из pg-orders»* → `requestChart()`;
   панель AG-UI чата раскроется автоматически, агент выполнит `SearchIndexTool`, и в чате
   появится inline-график (ECharts) вместе с численным ответом.
4. *«А теперь сгруппируй по регионам»* / *«Переключи на линейный график»* — multi-turn,
   история диалога сохраняется в той же сессии.
5. *«Сохрани визуализацию»* → сохранённая визуализация OSD, публичная ссылка и числа в ответе.

Реальные скриншоты этих сценариев: [docs/examples.md](docs/examples.md).

## Тесты

```bash
# все модули из корня: unit + integration + ArchUnit + SpotBugs gate
./services/pg-opensearch-connector/mvnw -f pom.xml verify -pl '!e2e-tests'
# или по модулям (Testcontainers: PostgreSQL 16 + OpenSearch 3.10.0)
(cd services/pg-opensearch-connector && ./mvnw test)   # 23 теста
(cd services/mcp-bridge-server && ./mvnw test)         # 25 тестов
(cd services/agui-adapter && ./mvnw test)              # 11 тестов
(cd services/mock-llm && ./mvnw test)                  # 6 тестов

# E2E против развёрнутого стенда (JUnit 5 + Playwright + MCP-клиент)
(cd e2e-tests && ./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium")
(cd e2e-tests && ./mvnw test)                 # mock-LLM стенд
(cd e2e-tests && ./mvnw test -DrealLlm=true)  # real-LLM стенд (OpenRouter и т.п.)
```

E2E-сценарии: синхронизация PG→OpenSearch ≤15 с; полный цикл
MCP→Playwright→AG-UI (чат открылся, промпт и ответ в DOM, canvas графика виден,
без ошибок консоли); невалидный `sessionId` → структурированная ошибка.
Дополнительно: multi-turn — две команды `requestChart`
в одной сессии (pie по регионам → line по категориям), история чата сохраняется,
тип графика проверяется в DOM по `data-chart-type`; save-flow (только real-LLM) —
явное намерение сохранить → `CreateVisualizationTool`, публичная ссылка и сохранённый
объект в OSD, `getDashboardContext` отдаёт активный временной диапазон из `_g` URL.

## Особенности OSD 3.10 (учтено в `deploy/docker/opensearch_dashboards.yml`)

- `workspace.enabled: false` — иначе чат требует привязки data sources к workspace.
- `data_source.enabled: true` + provisioning saved-object `data-source` и выбор его в плагине —
  иначе чат блокирует отправку («no data sources» / «Select a data source to continue…»).
- `contextProvider.enabled: true` — обязательное условие включения chat-плагина.
- `chat.observabilityAgentId` — без него memory-маршруты чата отдают 503 («ML router not available»).
- `csp.rules` — массив строк; `csp.strict: false` + `csp.allowedConnectSources` для WebSocket моста.
- Агент регистрируется с `agentic_memory` + memory container (история чата).
- Исполнение AG-UI идёт через `agui-adapter`: в 3.10.0 REST-движок не поддерживает
  streaming-каналы, нативный `_execute/stream` AG_UI-агента неисполним.
- Временные ряды в чате рисует встроенный frontend-tool `graph_timeseries_data` (только line):
  адаптер эмитит `TOOL_CALL_START/ARGS/END` + `TOOL_CALL_RESULT` (с `graphData`) и текст в том же
  run; run без текста закрывает окно чата.
- Категориальные графики (bar/pie/line) рисует собственный action плагина `chart_pilot_chart`
  на ECharts: адаптер эмитит только `TOOL_CALL_START/ARGS/END` по terms-агрегации,
  результат возвращает клиентский handler; тип графика выводится из текста вопроса.
  Сохранённые визуализации в Dashboards — `CreateVisualizationTool` (числа + ссылка в ответе).
  Демо-дашборд вне чата: `deploy/scripts/provision-demo-dashboard.sh`.

## Безопасность (важно)

Это **dev-стенд**: OpenSearch Security отключён, MCP/WS без аутентификации,
дефолтные пароли PostgreSQL. Применены mitigations:
белый список Origin для WS, 128-битные sessionId, allowlist локальных `bridgeUrl`,
узкий CSP, маскирование секретов, `show-details: when_authorized`, DLQ и MVCC safety window.
**Не выставляйте стенд в публичную сеть.** Production-харденинг (OAuth2/JWT, wsToken,
Redis для replicas>1, Flyway/CDC) — следующие этапы production-харденинга.

## Структура репозитория

```
services/pg-opensearch-connector/   синхронизация PG → OpenSearch
services/mcp-bridge-server/         MCP-сервер + WebSocket-мост
services/agui-adapter/              AG-UI SSE-фасад + agent-loop
services/mock-llm/                  OpenAI-совместимый мок для E2E
osd-plugin/chart_pilot_bridge/      client-only плагин OSD (TypeScript)
deploy/docker/                      Dockerfile'ы (включая osd-with-plugin)
deploy/k8s/                         манифесты Minikube
deploy/scripts/                     deploy-minikube.sh, build-osd-bootstrap.sh, register-agui-agent.sh,
                                    provision-demo-dashboard.sh
e2e-tests/                          JUnit 5 + Playwright E2E
docs/                               examples, tools, assets
```