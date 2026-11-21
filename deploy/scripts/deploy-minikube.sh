#!/usr/bin/env bash
# ChartPilot MCP: сборка образов и развёртывание всего стека в выделенном профиле Minikube.
# Выделенный профиль chart-pilot не трогает другие кластеры пользователя.
# Запуск: ./deploy/scripts/deploy-minikube.sh   (E2E_MODE=true по умолчанию — mock-LLM)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
K8S_DIR="${ROOT_DIR}/deploy/k8s"
PROFILE="chart-pilot"
NAMESPACE="chart-pilot"
E2E_MODE="${E2E_MODE:-true}"
KUBECTL="kubectl --context=${PROFILE}"

for cmd in minikube kubectl docker curl jq sed; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Ошибка: утилита '$cmd' не установлена." >&2
    exit 1
  fi
done

# 1. Выделенный кластер Minikube
if ! minikube -p "${PROFILE}" status >/dev/null 2>&1; then
  echo "==> Запуск Minikube-профиля '${PROFILE}' (7 GB RAM, 4 CPU, containerd)..."
  # containerd: docker-runtime внутри kicbase на этой машине падает (docker.service masked).
  # Образы загружаются через 'minikube image load', docker-env не требуется.
  minikube start -p "${PROFILE}" --driver=docker --container-runtime=containerd --memory=7168 --cpus=4
fi
MINIKUBE_IP="$(minikube -p "${PROFILE}" ip)"
# На WSL2 IP узла Minikube не маршрутизируется с хоста — публикуем NodePort'ы
# через socat-сайдкары в сети кластера (docker -p.bind на 127.0.0.1 хоста).
# Браузер Windows попадает сюда через WSL localhost-forwarding.
for svc_port in 30560 30880 30432 30920; do
  name="chartpilot-proxy-${svc_port}"
  docker rm -f "${name}" >/dev/null 2>&1 || true
  docker run -d --name "${name}" --network="${PROFILE}" \
    -p "127.0.0.1:${svc_port}:${svc_port}" \
    alpine/socat "tcp-listen:${svc_port},fork,reuseaddr" "tcp-connect:${MINIKUBE_IP}:${svc_port}" >/dev/null
done
OSD_PUBLIC_URL="http://127.0.0.1:30560"
BRIDGE_WS_URL="ws://127.0.0.1:30880"
export NO_PROXY="127.0.0.1,localhost,${MINIKUBE_IP}"
export no_proxy="${NO_PROXY}"
echo "==> Minikube IP: ${MINIKUBE_IP}; публичные URL: ${OSD_PUBLIC_URL}, ${BRIDGE_WS_URL}"

# 2. Сборка образов на хосте (--network=host: bridge-сеть docker0 вешает загрузки в WSL2)
echo "==> Сборка pg-opensearch-connector:local..."
docker build --network=host -q -t pg-opensearch-connector:local -f "${ROOT_DIR}/services/pg-opensearch-connector/Dockerfile" "${ROOT_DIR}"
echo "==> Сборка mcp-bridge-server:local..."
docker build --network=host -q -t mcp-bridge-server:local -f "${ROOT_DIR}/services/mcp-bridge-server/Dockerfile" "${ROOT_DIR}"
echo "==> Сборка agui-adapter:local..."
docker build --network=host -q -t agui-adapter:local -f "${ROOT_DIR}/services/agui-adapter/Dockerfile" "${ROOT_DIR}"
echo "==> Сборка chartpilot/register-agent:local..."
docker build --network=host -q -t chartpilot/register-agent:local -f "${ROOT_DIR}/deploy/docker/register-agent.Dockerfile" "${ROOT_DIR}/deploy/docker"
if [[ "${E2E_MODE}" == "true" ]]; then
  echo "==> Сборка mock-llm:local..."
  docker build --network=host -q -t mock-llm:local -f "${ROOT_DIR}/services/mock-llm/Dockerfile" "${ROOT_DIR}"
fi
echo "==> Подготовка bootstrap-образа OSD (однократно)..."
bash "${SCRIPT_DIR}/build-osd-bootstrap.sh"
echo "==> Сборка osd-chart-pilot:local (плагин + официальный OSD 3.10.0)..."
docker build --network=host -q -t osd-chart-pilot:local -f "${ROOT_DIR}/deploy/docker/osd-with-plugin.Dockerfile" "${ROOT_DIR}"

# 3. Загрузка образов в кластер (containerd runtime: docker-env не используется)
IMAGES=(pg-opensearch-connector:local mcp-bridge-server:local agui-adapter:local osd-chart-pilot:local chartpilot/register-agent:local)
if [[ "${E2E_MODE}" == "true" ]]; then
  IMAGES+=(mock-llm:local)
fi
for img in "${IMAGES[@]}"; do
  echo "==> Загрузка образа ${img} в Minikube..."
  minikube -p "${PROFILE}" image load "${img}"
done

# 4. Базовые манифесты
echo "==> Применение манифестов в namespace '${NAMESPACE}'..."
${KUBECTL} apply -f "${K8S_DIR}/namespace.yaml"
${KUBECTL} apply -f "${K8S_DIR}/postgres.yaml"
${KUBECTL} apply -f "${K8S_DIR}/opensearch.yaml"
if [[ "${E2E_MODE}" == "true" ]]; then
  ${KUBECTL} apply -f "${K8S_DIR}/mock-llm.yaml"
fi
${KUBECTL} apply -f "${K8S_DIR}/pg-connector.yaml"
# Прокси для внешних LLM-endpoint'ов: прямой egress из кластера может блокироваться DPI.
LLM_PROXY_HOST="${LLM_PROXY_HOST:-}"
LLM_PROXY_PORT="${LLM_PROXY_PORT:-0}"
if [[ -z "${LLM_PROXY_HOST}" && -n "${HTTPS_PROXY:-}" ]]; then
  proxy_hostport="${HTTPS_PROXY#*://}"
  LLM_PROXY_HOST="${proxy_hostport%%:*}"
  LLM_PROXY_PORT="${proxy_hostport##*:}"
fi
sed -e "s|__LLM_PROXY_HOST__|${LLM_PROXY_HOST}|g" -e "s|__LLM_PROXY_PORT__|${LLM_PROXY_PORT}|g" \
  -e "s|__LLM_API_KEY__|${LLM_API_KEY:-}|g" -e "s|__OSD_PUBLIC_URL__|${OSD_PUBLIC_URL}|g" \
  "${K8S_DIR}/agui-adapter.yaml" | ${KUBECTL} apply -f -
sed -e "s|__OSD_PUBLIC_URL__|${OSD_PUBLIC_URL}|g" -e "s|__BRIDGE_WS_URL__|${BRIDGE_WS_URL}|g" \
  "${K8S_DIR}/mcp-bridge.yaml" | ${KUBECTL} apply -f -

echo "==> Ожидание готовности базовых сервисов..."
${KUBECTL} -n "${NAMESPACE}" rollout status deployment/postgres --timeout=180s
${KUBECTL} -n "${NAMESPACE}" rollout status deployment/opensearch --timeout=300s

# Миграция маппинга amount double -> scaled_float ( / PERF-03):
# индекс пересоздаётся, checkpoint сбрасывается, коннектор пересинхронизирует данные.
${KUBECTL} -n "${NAMESPACE}" port-forward svc/opensearch 19201:9200 >/dev/null 2>&1 &
MIG_PF=$!
for i in $(seq 1 20); do
  curl -fsS --noproxy '*' --max-time 3 'http://127.0.0.1:19201/' >/dev/null 2>&1 && break
  sleep 1
done
AMOUNT_TYPE="$(curl -fsS --noproxy '*' --max-time 5 'http://127.0.0.1:19201/pg-orders/_mapping' 2>/dev/null | jq -r '.["pg-orders"].mappings.properties.amount.type // "none"')"
if [[ "${AMOUNT_TYPE}" == "double" ]]; then
  echo "==> Миграция pg-orders: double -> scaled_float (пересоздание индекса)..."
  curl -fsS --noproxy '*' -X DELETE 'http://127.0.0.1:19201/pg-orders' >/dev/null
  ${KUBECTL} -n "${NAMESPACE}" exec deploy/postgres -- psql -U chartpilot -d chartpilot -c 'DROP TABLE IF EXISTS connector_checkpoint' >/dev/null
  ${KUBECTL} -n "${NAMESPACE}" rollout restart deployment/pg-opensearch-connector
fi
kill "${MIG_PF}" >/dev/null 2>&1 || true

if [[ "${E2E_MODE}" == "true" ]]; then
  ${KUBECTL} -n "${NAMESPACE}" rollout status deployment/mock-llm --timeout=120s
fi
${KUBECTL} -n "${NAMESPACE}" rollout status deployment/pg-opensearch-connector --timeout=180s
${KUBECTL} -n "${NAMESPACE}" rollout status deployment/agui-adapter --timeout=180s
${KUBECTL} -n "${NAMESPACE}" rollout status deployment/mcp-bridge-server --timeout=180s

# 5. Регистрация AG-UI агента (Init Job)
if [[ "${E2E_MODE}" == "true" ]]; then
  LLM_ENDPOINT="http://mock-llm:8085/v1/chat/completions"
  LLM_MODEL="${LLM_MODEL:-mock-gpt-4o}"
  LLM_API_KEY="${LLM_API_KEY:-mock-key}"
else
  LLM_ENDPOINT="${LLM_ENDPOINT:-https://api.openai.com/v1/chat/completions}"
  LLM_MODEL="${LLM_MODEL:-gpt-4o}"
  if [[ -z "${LLM_API_KEY:-}" ]]; then
    echo "Ошибка: для E2E_MODE=false задайте LLM_API_KEY." >&2
    exit 1
  fi
fi
echo "==> Регистрация AG-UI агента (LLM_ENDPOINT=${LLM_ENDPOINT})..."
${KUBECTL} -n "${NAMESPACE}" delete job register-agui-agent --ignore-not-found
${KUBECTL} -n "${NAMESPACE}" create configmap register-agui-script \
  --from-file=register-agui-agent.sh="${SCRIPT_DIR}/register-agui-agent.sh" \
  --dry-run=client -o yaml | ${KUBECTL} apply -f -
sed -e "s|__LLM_ENDPOINT__|${LLM_ENDPOINT}|g" -e "s|__LLM_MODEL__|${LLM_MODEL}|g" -e "s|__LLM_API_KEY__|${LLM_API_KEY}|g" -e "s|__E2E_MODE__|${E2E_MODE}|g" \
  "${K8S_DIR}/register-agui-job.yaml" | ${KUBECTL} apply -f -
if ! ${KUBECTL} -n "${NAMESPACE}" wait --for=condition=complete job/register-agui-agent --timeout=300s; then
  echo "Ошибка: Job регистрации агента не завершился успешно:" >&2
  ${KUBECTL} -n "${NAMESPACE}" logs job/register-agui-agent --tail=30 >&2 || true
  exit 1
fi
# Логи берём из успешного пода Job: при ретраях job/... может указывать на упавший под
REG_POD="$(${KUBECTL} -n "${NAMESPACE}" get pods -l job-name=register-agui-agent \
  --field-selector=status.phase=Succeeded -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"
if [[ -z "${REG_POD}" ]]; then
  REG_POD="job/register-agui-agent"
fi
AGENT_ID="$(${KUBECTL} -n "${NAMESPACE}" logs "${REG_POD}" | grep -Eo 'AGENT_ID=[A-Za-z0-9_-]+' | tail -n1 | cut -d= -f2)"
if [[ -z "${AGENT_ID}" ]]; then
  echo "Ошибка: не удалось получить AGENT_ID из логов Job." >&2
  exit 1
fi
echo "==> AGENT_ID=${AGENT_ID}"
# Адаптер кэширует конфиг коннектора в памяти — перезапускаем после смены LLM
${KUBECTL} -n "${NAMESPACE}" rollout restart deployment/agui-adapter
${KUBECTL} -n "${NAMESPACE}" rollout status deployment/agui-adapter --timeout=180s

# 6. OpenSearch Dashboards с плагином и конфигурацией AG-UI
${KUBECTL} -n "${NAMESPACE}" create configmap osd-agui-config \
  --from-literal=AGENT_ID="${AGENT_ID}" \
  --from-literal=AG_UI_URL="http://agui-adapter:8086/agui" \
  --dry-run=client -o yaml | ${KUBECTL} apply -f -
${KUBECTL} apply -f "${K8S_DIR}/opensearch-dashboards.yaml"
# ConfigMap osd-agui-config мог обновиться (новый AGENT_ID) — перезапускаем OSD.
${KUBECTL} -n "${NAMESPACE}" rollout restart deployment/opensearch-dashboards
echo "==> Ожидание готовности OpenSearch Dashboards..."
${KUBECTL} -n "${NAMESPACE}" rollout status deployment/opensearch-dashboards --timeout=300s

# 6.1 Data source для чата: saved-object 'data-source' + defaultDataSourceId.
# Чат OSD 3.10 блокирует отправку без хотя бы одного источника данных.
echo "==> Provisioning data source для чата..."
DSID="$(curl -fsS --noproxy '*' --max-time 10 -H 'osd-xsrf: true' \
  "${OSD_PUBLIC_URL}/api/saved_objects/_find?type=data-source&per_page=1" 2>/dev/null | jq -r '.saved_objects[0].id // empty')"
if [[ -z "${DSID}" ]]; then
  DSID="$(curl -fsS --noproxy '*' --max-time 10 -X POST "${OSD_PUBLIC_URL}/api/saved_objects/data-source" \
    -H 'Content-Type: application/json' -H 'osd-xsrf: true' \
    -d '{"attributes":{"title":"ChartPilot Local OpenSearch","description":"Local cluster for ChartPilot","dataSourceEngineType":"OpenSearch","endpoint":"http://opensearch:9200","auth":{"type":"no_auth"}}}' | jq -r '.id // empty')"
fi
if [[ -n "${DSID}" ]]; then
  curl -fsS --noproxy '*' --max-time 10 -X POST "${OSD_PUBLIC_URL}/api/ui_settings/settings" \
    -H 'Content-Type: application/json' -H 'osd-xsrf: true' \
    -d "{\"changes\":{\"defaultDataSourceId\":\"${DSID}\"}}" >/dev/null 2>&1 \
  || curl -fsS --noproxy '*' --max-time 10 -X PUT "${OSD_PUBLIC_URL}/api/ui_settings/settings" \
    -H 'Content-Type: application/json' -H 'osd-xsrf: true' \
    -d "{\"changes\":{\"defaultDataSourceId\":\"${DSID}\"}}" >/dev/null 2>&1 || true
  echo "    DSID=${DSID}"
else
  echo "    Внимание: не удалось создать data source; чат может блокировать отправку." >&2
fi

# 6.2 Демо-дашборд (index-pattern + визуализация + дашборд), идемпотентно
bash "${SCRIPT_DIR}/provision-demo-dashboard.sh" "${OSD_PUBLIC_URL}" "http://${MINIKUBE_IP}:30920" \
  || echo "    Внимание: provisioning демо-дашборда не удался (не критично для E2E)." >&2

# 7. Проверка синхронизации pg-orders (retry вместо слепого sleep — REL-03)
echo "==> Проверка индекса pg-orders..."
${KUBECTL} -n "${NAMESPACE}" port-forward svc/opensearch 19200:9200 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill ${PF_PID} >/dev/null 2>&1 || true' EXIT
DOC_COUNT=""
for i in $(seq 1 20); do
  DOC_COUNT="$(curl -fsS --noproxy '*' --max-time 3 'http://127.0.0.1:19200/pg-orders/_count' 2>/dev/null | jq -r '.count')" && break
  sleep 1
done
kill "${PF_PID}" >/dev/null 2>&1 || true
trap - EXIT
if [[ -z "${DOC_COUNT}" ]]; then
  echo "Ошибка: не удалось получить count pg-orders через port-forward." >&2
  exit 1
fi
echo "==> Документов в pg-orders: ${DOC_COUNT}"

# 8. Итоговые URL и команды подключения
echo ""
echo "=================================================================="
echo " ChartPilot MCP развёрнут в Minikube (профиль ${PROFILE})!"
echo "=================================================================="
echo " OpenSearch Dashboards : ${OSD_PUBLIC_URL}"
echo " MCP Bridge Server     : http://127.0.0.1:30880"
echo " AG-UI Agent ID        : ${AGENT_ID}"
echo " Документов pg-orders  : ${DOC_COUNT}"
echo ""
echo " Подключение в Qwen Code:"
echo "   qwen mcp add --transport sse chart-pilot http://127.0.0.1:30880/sse --timeout 60000"
echo "=================================================================="