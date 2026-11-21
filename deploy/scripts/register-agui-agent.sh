#!/bin/sh
# Регистрация AG-UI агента ChartPilot в OpenSearch ML Commons (docs/MINKUBE.md, ).
# POSIX sh — выполняется в Job-контейнере chartpilot/register-agent (curl + jq).
# Идемпотентен (REL-03): повторный запуск переиспользует коннектор/модель/агент по имени.
set -eu

OPENSEARCH_URL="${OPENSEARCH_URL:-http://opensearch:9200}"
LLM_ENDPOINT="${LLM_ENDPOINT:-http://mock-llm:8085/v1/chat/completions}"
LLM_MODEL="${LLM_MODEL:-mock-gpt-4o}"
LLM_API_KEY="${LLM_API_KEY:-mock-key}"
E2E_MODE="${E2E_MODE:-true}"
AUTH=""
if [ -n "${OPENSEARCH_USER:-}" ] && [ -n "${OPENSEARCH_PASS:-}" ]; then
  AUTH="-u ${OPENSEARCH_USER}:${OPENSEARCH_PASS}"
fi

find_by_name() {
  curl -fsS -X POST $AUTH "${OPENSEARCH_URL}/_plugins/_ml/$1/_search" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg n "$2" '{query:{match:{name:$n}},size:1}')" 2>/dev/null \
    | jq -r '.hits.hits[0]._id // empty'
}

echo "==> Ожидание готовности OpenSearch (${OPENSEARCH_URL})..."
i=0
until curl -fsS $AUTH "${OPENSEARCH_URL}/_cluster/health" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -gt 100 ]; then
    echo "Ошибка: OpenSearch не стал доступен за 300 с" >&2
    exit 1
  fi
  sleep 3
done
echo "==> OpenSearch доступен."

echo "==> 1. Настройка ML Commons и доверенных эндпоинтов..."
# SEC-03: private_ip_enabled и mock-endpoint включаются только в E2E-стенде.
# Trusted endpoints: хост текущего LLM_ENDPOINT + известные провайдеры (+ mock в E2E).
LLM_HOST="$(printf '%s' "${LLM_ENDPOINT}" | sed -E 's#^https?://([^/:]+).*#\1#' | sed 's/\./\\./g')"
TRUSTED_REGEX="$(jq -n --arg host "^https?://${LLM_HOST}/.*$" --argjson e2e "$([ "${E2E_MODE}" = "true" ] && echo true || echo false)" '
  [$host, "^https://api\\.openai\\.com/.*$", "^https://openrouter\\.ai/.*$"]
  + (if $e2e then ["^http://mock-llm:8085/.*$"] else [] end)')"
SETTINGS=$(jq -n --argjson e2e "$([ "${E2E_MODE}" = "true" ] && echo true || echo false)" --argjson trusted "${TRUSTED_REGEX}" '{
  persistent: {
    "plugins.ml_commons.agent_framework_enabled": true,
    "plugins.ml_commons.unified_agent_api_enabled": true,
    "plugins.ml_commons.stream_enabled": true,
    "plugins.ml_commons.ag_ui_enabled": true,
    "plugins.ml_commons.only_run_on_ml_node": false,
    "plugins.ml_commons.connector.private_ip_enabled": $e2e,
    "plugins.ml_commons.trusted_connector_endpoints_regex": $trusted
  }
}')
curl -fsS -X PUT $AUTH "${OPENSEARCH_URL}/_cluster/settings" \
  -H "Content-Type: application/json" -d "${SETTINGS}" | jq -c .

echo "==> 2. Коннектор к LLM (${LLM_ENDPOINT})..."
CONNECTOR_ID="$(find_by_name connectors 'ChartPilot LLM Connector')"
if [ -n "${CONNECTOR_ID}" ]; then
  CURRENT_ENDPOINT="$(curl -fsS $AUTH "${OPENSEARCH_URL}/_plugins/_ml/connectors/${CONNECTOR_ID}" | jq -r '.parameters.endpoint // empty')"
  CURRENT_MODEL="$(curl -fsS $AUTH "${OPENSEARCH_URL}/_plugins/_ml/connectors/${CONNECTOR_ID}" | jq -r '.parameters.model // empty')"
  if [ "${CURRENT_ENDPOINT}" != "${LLM_ENDPOINT}" ] || [ "${CURRENT_MODEL}" != "${LLM_MODEL}" ]; then
    echo "    Коннектор изменился (${CURRENT_ENDPOINT}/${CURRENT_MODEL} -> ${LLM_ENDPOINT}/${LLM_MODEL}); пересоздаю коннектор/модель/агент..."
    OLD_AGENT="$(find_by_name agents 'ChartPilot AG-UI Agent')"
    if [ -n "${OLD_AGENT}" ]; then
      curl -fsS -X DELETE $AUTH "${OPENSEARCH_URL}/_plugins/_ml/agents/${OLD_AGENT}" >/dev/null
    fi
    OLD_MODEL="$(find_by_name models 'ChartPilot LLM Model')"
    if [ -n "${OLD_MODEL}" ]; then
      # ML Commons запрещает удалять задеплоенную модель — сначала undeploy,
      # затем ждём UNDEPLOYED (undeploy асинхронный, иначе delete даёт 409)
      curl -fsS -X POST $AUTH "${OPENSEARCH_URL}/_plugins/_ml/models/${OLD_MODEL}/_undeploy" >/dev/null 2>&1 || true
      i=0
      until [ "$(curl -fsS $AUTH "${OPENSEARCH_URL}/_plugins/_ml/models/${OLD_MODEL}" 2>/dev/null | jq -r '.model_state // "UNDEPLOYED"')" = "UNDEPLOYED" ]; do
        i=$((i + 1))
        if [ "$i" -gt 60 ]; then
          echo "Ошибка: модель ${OLD_MODEL} не перешла в UNDEPLOYED за 60 с" >&2
          exit 1
        fi
        sleep 1
      done
      curl -fsS -X DELETE $AUTH "${OPENSEARCH_URL}/_plugins/_ml/models/${OLD_MODEL}" >/dev/null
    fi
    curl -fsS -X DELETE $AUTH "${OPENSEARCH_URL}/_plugins/_ml/connectors/${CONNECTOR_ID}" >/dev/null
    CONNECTOR_ID=""
  else
    echo "    Переиспользую CONNECTOR_ID=${CONNECTOR_ID}"
  fi
fi
if [ -z "${CONNECTOR_ID}" ]; then
  CONNECTOR_PAYLOAD=$(jq -n \
    --arg endpoint "${LLM_ENDPOINT}" \
    --arg model "${LLM_MODEL}" \
    --arg key "${LLM_API_KEY}" \
    '{
      name: "ChartPilot LLM Connector",
      description: "Коннектор к OpenAI-совместимому API",
      version: 1,
      protocol: "http",
      parameters: { endpoint: $endpoint, model: $model },
      credential: { openAI_key: $key },
      actions: [
        {
          action_type: "predict",
          method: "POST",
          url: "${parameters.endpoint}",
          headers: {
            Authorization: "Bearer ${credential.openAI_key}",
            "Content-Type": "application/json"
          },
          request_body: "{ \"model\": \"${parameters.model}\", \"messages\": ${parameters.messages}, \"tools\": ${parameters.tools:-[]}, \"stream\": ${parameters.stream:-false} }"
        }
      ]
    }')
  CONNECTOR_ID=$(curl -fsS -X POST $AUTH "${OPENSEARCH_URL}/_plugins/_ml/connectors/_create" \
    -H "Content-Type: application/json" -d "${CONNECTOR_PAYLOAD}" | jq -r '.connector_id')
  echo "    CONNECTOR_ID=${CONNECTOR_ID}"
fi

echo "==> 3. Регистрация и деплой модели..."
MODEL_ID="$(find_by_name models 'ChartPilot LLM Model')"
if [ -n "${MODEL_ID}" ]; then
  echo "    Переиспользую MODEL_ID=${MODEL_ID}"
  STATE=$(curl -fsS $AUTH "${OPENSEARCH_URL}/_plugins/_ml/models/${MODEL_ID}" | jq -r '.model_state')
  if [ "${STATE}" != "DEPLOYED" ]; then
    curl -fsS -X POST $AUTH "${OPENSEARCH_URL}/_plugins/_ml/models/${MODEL_ID}/_deploy" >/dev/null
  fi
else
  MODEL_PAYLOAD=$(jq -n --arg cid "${CONNECTOR_ID}" \
    '{name: "ChartPilot LLM Model", function_name: "remote", description: "Модель для AG-UI агента", connector_id: $cid}')
  MODEL_ID=$(curl -fsS -X POST $AUTH "${OPENSEARCH_URL}/_plugins/_ml/models/_register?deploy=true" \
    -H "Content-Type: application/json" -d "${MODEL_PAYLOAD}" | jq -r '.model_id')
  echo "    MODEL_ID=${MODEL_ID}"
fi

echo "==> Ожидание деплоя модели..."
i=0
until [ "$(curl -fsS $AUTH "${OPENSEARCH_URL}/_plugins/_ml/models/${MODEL_ID}" | jq -r '.model_state')" = "DEPLOYED" ]; do
  i=$((i + 1))
  if [ "$i" -gt 60 ]; then
    echo "Ошибка: модель не задеплоилась за 120 с" >&2
    exit 1
  fi
  sleep 2
done

echo "==> 4. Memory container (agentic memory для chat-истории OSD)..."
CONTAINER_ID="$(curl -fsS -X POST $AUTH "${OPENSEARCH_URL}/_plugins/_ml/memory_containers/_search" \
  -H "Content-Type: application/json" \
  -d "$(jq -n '{query:{match:{name:"chartpilot-container"}},size:1}')" | jq -r '.hits.hits[0]._id // empty')"
if [ -z "${CONTAINER_ID}" ]; then
  CONTAINER_ID=$(curl -fsS -X POST $AUTH "${OPENSEARCH_URL}/_plugins/_ml/memory_containers/_create" \
    -H "Content-Type: application/json" \
    -d '{"name":"chartpilot-container","description":"ChartPilot agentic memory"}' | jq -r '.memory_container_id')
fi
echo "    CONTAINER_ID=${CONTAINER_ID}"

echo "==> 5. AG-UI агент с Backend tools и agentic memory..."
AGENT_ID="$(find_by_name agents 'ChartPilot AG-UI Agent')"
if [ -n "${AGENT_ID}" ]; then
  MEM_TYPE=$(curl -fsS $AUTH "${OPENSEARCH_URL}/_plugins/_ml/agents/${AGENT_ID}" | jq -r '.memory.type // empty')
  if [ "${MEM_TYPE}" != "agentic_memory" ]; then
    echo "    Агент ${AGENT_ID} имеет memory=${MEM_TYPE:-none}; пересоздаю с agentic_memory..."
    curl -fsS -X DELETE $AUTH "${OPENSEARCH_URL}/_plugins/_ml/agents/${AGENT_ID}" >/dev/null
    AGENT_ID=""
  else
    echo "    Переиспользую AGENT_ID=${AGENT_ID}"
  fi
fi
if [ -z "${AGENT_ID}" ]; then
  AGENT_PAYLOAD=$(jq -n --arg mid "${MODEL_ID}" --arg cid "${CONTAINER_ID}" \
    '{
      name: "ChartPilot AG-UI Agent",
      type: "AG_UI",
      description: "Агент для анализа индекса pg-orders и построения графиков в OpenSearch Dashboards",
      llm: {
        model_id: $mid,
        parameters: {
          max_iteration: 10,
          system_prompt: "Ты аналитик данных OpenSearch. Используй ListIndexTool, IndexMappingTool и SearchIndexTool для работы с индексом pg-orders, затем визуализируй результат пользователю."
        }
      },
      tools: [
        { type: "ListIndexTool" },
        { type: "IndexMappingTool" },
        { type: "SearchIndexTool" }
      ],
      memory: { type: "agentic_memory", memory_container_id: $cid },
      parameters: { include_token_usage: true }
    }')
  AGENT_ID=$(curl -fsS -X POST $AUTH "${OPENSEARCH_URL}/_plugins/_ml/agents/_register" \
    -H "Content-Type: application/json" -d "${AGENT_PAYLOAD}" | jq -r '.agent_id')
fi

echo "==> Готово! AG-UI агент:"
echo "    AGENT_ID=${AGENT_ID}"
echo "    AG_UI_URL=http://agui-adapter:8086/agui (нативный _execute/stream недоступен в 3.10.0)"