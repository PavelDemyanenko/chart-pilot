#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
K8S_DIR="${ROOT_DIR}/deploy/k8s"
NAMESPACE="chart-pilot"
E2E_MODE="${E2E_MODE:-true}"

# Проверка необходимых утилит
for cmd in minikube kubectl docker curl jq; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Ошибка: утилита '$cmd' не установлена." >&2
    exit 1
  fi
done

# 1. Проверка и запуск Minikube
if ! minikube status >/dev/null 2>&1; then
  echo "==> Minikube не запущен. Запускаю кластер (8GB RAM, 4 CPU)..."
  minikube start --memory=8192 --cpus=4
fi

echo "==> Переключение на Docker-окружение Minikube..."
eval "$(minikube -p minikube docker-env)"

# 2. Сборка локальных Docker-образов
echo "==> Сборка образов..."
docker build -t pg-opensearch-connector:local "${ROOT_DIR}/services/pg-opensearch-connector"
docker build -t mcp-bridge-server:local "${ROOT_DIR}/services/mcp-bridge-server"
docker build -t osd-chart-pilot:local -f "${ROOT_DIR}/deploy/docker/osd-with-plugin.Dockerfile" "${ROOT_DIR}"

if [[ "${E2E_MODE}" == "true" && -d "${ROOT_DIR}/services/mock-llm" ]]; then
  docker build -t mock-llm:local "${ROOT_DIR}/services/mock-llm"
fi

# 3. Применение базовых Kubernetes-манифестов
echo "==> Применение манифестов в namespace '${NAMESPACE}'..."
kubectl apply -f "${K8S_DIR}/namespace.yaml"
kubectl apply -f "${K8S_DIR}/postgres.yaml"
kubectl apply -f "${K8S_DIR}/opensearch.yaml"

if [[ "${E2E_MODE}" == "true" && -f "${K8S_DIR}/mock-llm.yaml" ]]; then
  kubectl apply -f "${K8S_DIR}/mock-llm.yaml"
fi

kubectl apply -f "${K8S_DIR}/pg-connector.yaml"
kubectl apply -f "${K8S_DIR}/mcp-bridge.yaml"

echo "==> Ожидание готовности OpenSearch и базовых сервисов..."
kubectl rollout status deployment/postgres -n "${NAMESPACE}" --timeout=180s
kubectl rollout status deployment/opensearch -n "${NAMESPACE}" --timeout=300s
if [[ "${E2E_MODE}" == "true" && -f "${K8S_DIR}/mock-llm.yaml" ]]; then
  kubectl rollout status deployment/mock-llm -n "${NAMESPACE}" --timeout=120s
fi

# 4. Регистрация AG-UI агента через временный port-forward
echo "==> Проброс порта OpenSearch для регистрации AG-UI агента..."
kubectl port-forward svc/opensearch 19200:9200 -n "${NAMESPACE}" >/dev/null 2>&1 &
PF_PID=$!
trap 'kill ${PF_PID} >/dev/null 2>&1 || true' EXIT
sleep 3

REGISTER_OUTPUT=$(
  OPENSEARCH_URL="http://127.0.0.1:19200" \
  bash "${SCRIPT_DIR}/register-agui-agent.sh"
)
echo "${REGISTER_OUTPUT}"

AGENT_ID=$(echo "${REGISTER_OUTPUT}" | grep -Eo 'AGENT_ID=[^ ]+' | cut -d'=' -f2 | tail -n1)
if [[ -z "${AGENT_ID}" ]]; then
  echo "Ошибка: не удалось получить AGENT_ID из вывода register-agui-agent.sh" >&2
  exit 1
fi

kill "${PF_PID}" >/dev/null 2>&1 || true

# 5. Передача AGENT_ID в ConfigMap и запуск OpenSearch Dashboards
echo "==> Обновление конфигурации OpenSearch Dashboards (AGENT_ID=${AGENT_ID})..."
kubectl create configmap osd-agui-config \
  -n "${NAMESPACE}" \
  --from-literal=AGENT_ID="${AGENT_ID}" \
  --from-literal=AG_UI_URL="http://opensearch:9200/_plugins/_ml/agents/${AGENT_ID}/_execute/stream" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -f "${K8S_DIR}/opensearch-dashboards.yaml"

echo "==> Ожидание готовности всех оставшихся подов..."
kubectl rollout status deployment/pg-opensearch-connector -n "${NAMESPACE}" --timeout=180s
kubectl rollout status deployment/mcp-bridge-server -n "${NAMESPACE}" --timeout=180s
kubectl rollout status deployment/opensearch-dashboards -n "${NAMESPACE}" --timeout=240s

# 6. Вывод URL и команды подключения
OSD_URL=$(minikube service opensearch-dashboards -n "${NAMESPACE}" --url | head -n1)
MCP_URL=$(minikube service mcp-bridge-server -n "${NAMESPACE}" --url | head -n1)

echo ""
echo "=================================================================="
echo " ChartPilot MCP успешно развёрнут в Minikube!"
echo "=================================================================="
echo " OpenSearch Dashboards : ${OSD_URL}"
echo " MCP Bridge Server     : ${MCP_URL}"
echo " AG-UI Agent ID        : ${AGENT_ID}"
echo ""
echo " Подключение в Qwen Code:"
echo "   qwen mcp add --transport sse chart-pilot ${MCP_URL}/sse --timeout 60000"
echo "=================================================================="