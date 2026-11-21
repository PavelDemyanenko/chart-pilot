#!/usr/bin/env bash
# Идемпотентный provisioning демо-объектов OSD: index-pattern pg-orders (с fields),
# визуализация "Сумма заказов по категориям" и дашборд "ChartPilot — заказы" (30 дней).
# Запуск: ./deploy/scripts/provision-demo-dashboard.sh [OSD_URL] [OPENSEARCH_URL]
set -euo pipefail

OSD_URL="${1:-http://127.0.0.1:30560}"
OS_URL="${2:-http://127.0.0.1:30920}"
HDR=(-H 'Content-Type: application/json' -H 'osd-xsrf: true')
CURL=(curl -fsS --noproxy '*' --max-time 20)

find_id() { # type, title
  "${CURL[@]}" "${HDR[@]}" "${OSD_URL}/api/saved_objects/_find?type=$1&search_fields=title&search=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))' "$2")&per_page=1" \
    | jq -r '.saved_objects[0].id // empty'
}

IP_ID="$(find_id index-pattern pg-orders)"
if [[ -z "${IP_ID}" ]]; then
  IP_ID="$("${CURL[@]}" "${HDR[@]}" -X POST "${OSD_URL}/api/saved_objects/index-pattern" \
    -d '{"attributes":{"title":"pg-orders","timeFieldName":"created_at"}}' | jq -r '.id')"
fi
# fields обязательны: без них визуализации падают (vislib: data.series.find is not a function)
FIELDS="$("${CURL[@]}" "${OS_URL}/pg-orders/_field_caps?fields=*" | jq -c '[.fields | to_entries[] | {name: .key, type: (if .value.date then "date" elif .value.keyword then "string" elif (.value.long or .value.double or .value.scaled_float or .value.integer) then "number" else "string" end), esTypes: [.value | keys[] | select(. != "searchable" and . != "aggregatable")], count: 0, scripted: false, searchable: true, aggregatable: true, readFromDocValues: true}]')"
"${CURL[@]}" "${HDR[@]}" -X PUT "${OSD_URL}/api/saved_objects/index-pattern/${IP_ID}" \
  -d "{\"attributes\":{\"title\":\"pg-orders\",\"timeFieldName\":\"created_at\",\"fields\":$(jq -Rs . <<<"${FIELDS}")}}" >/dev/null
echo "index-pattern: ${IP_ID}"

VIS_ID="$(find_id visualization 'Сумма заказов по категориям')"
if [[ -z "${VIS_ID}" ]]; then
  VIS_STATE='{"title":"Сумма заказов по категориям","type":"histogram","params":{"type":"histogram","grid":{"categoryLines":false},"categoryAxes":[{"id":"CategoryAxis-1","type":"category","position":"bottom","show":true,"style":{},"scale":{"type":"linear"},"labels":{"show":true,"filter":true,"truncate":100},"title":{}}],"valueAxes":[{"id":"ValueAxis-1","name":"LeftAxis-1","type":"value","position":"left","show":true,"style":{},"scale":{"type":"linear","mode":"normal"},"labels":{"show":true,"rotate":0,"filter":false,"truncate":100},"title":{"text":"Sum of amount"}}],"seriesParams":[{"show":true,"type":"histogram","mode":"stacked","data":{"label":"Sum of amount","id":"1"},"valueAxis":"ValueAxis-1","drawLinesBetweenPoints":true,"lineWidth":2,"showCircles":true}],"addTooltip":true,"addLegend":true,"legendPosition":"right","times":[],"addTimeMarker":false,"labels":{}},"aggs":[{"id":"1","enabled":true,"type":"sum","params":{"field":"amount"},"schema":"metric"},{"id":"2","enabled":true,"type":"terms","params":{"field":"category","orderBy":"1","order":"desc","size":10,"otherBucket":false,"otherBucketLabel":"Other","missingBucket":false,"missingBucketLabel":"Missing"},"schema":"segment"}]}'
  VIS_ID="$("${CURL[@]}" "${HDR[@]}" -X POST "${OSD_URL}/api/saved_objects/visualization" \
    -d "$(jq -n --arg vs "${VIS_STATE}" --arg ip "${IP_ID}" '{attributes:{title:"Сумма заказов по категориям",description:"ChartPilot demo",visState:$vs,kibanaSavedObjectMeta:{searchSourceJSON:"{\"query\":{\"query\":\"\",\"language\":\"kuery\"},\"filter\":[],\"indexRefName\":\"kibanaSavedObjectMeta.searchSourceJSON.index\"}"}},references:[{name:"kibanaSavedObjectMeta.searchSourceJSON.index",type:"index-pattern",id:$ip}]}')" | jq -r '.id')"
fi
echo "visualization: ${VIS_ID}"

DASH_ID="$(find_id dashboard 'ChartPilot — заказы')"
PANELS="[{\"embeddableConfig\":{},\"panelIndex\":\"1\",\"gridData\":{\"x\":0,\"y\":0,\"w\":24,\"h\":15,\"i\":\"1\"},\"panelRefName\":\"panel_1\",\"type\":\"visualization\",\"version\":\"3.10.0\"}]"
if [[ -z "${DASH_ID}" ]]; then
  DASH_ID="$("${CURL[@]}" "${HDR[@]}" -X POST "${OSD_URL}/api/saved_objects/dashboard" \
    -d "$(jq -n --arg p "${PANELS}" --arg v "${VIS_ID}" '{attributes:{title:"ChartPilot — заказы",description:"Демо-дашборд ChartPilot MCP",panelsJSON:$p,optionsJSON:"{\"useMargins\":true,\"syncColors\":false,\"hidePanelTitles\":false}",timeRestore:true,timeFrom:"now-30d",timeTo:"now",kibanaSavedObjectMeta:{searchSourceJSON:"{\"query\":{\"query\":\"\",\"language\":\"kuery\"},\"filter\":[]}"}},references:[{name:"panel_1",type:"visualization",id:$v}]}')" | jq -r '.id')"
else
  "${CURL[@]}" "${HDR[@]}" -X PUT "${OSD_URL}/api/saved_objects/dashboard/${DASH_ID}" \
    -d "{\"attributes\":{\"panelsJSON\":$(jq -Rs . <<<"${PANELS}"),\"timeRestore\":true,\"timeFrom\":\"now-30d\",\"timeTo\":\"now\"}}" >/dev/null
fi
echo "dashboard: ${DASH_ID}"
echo "URL: ${OSD_URL}/app/dashboards#/view/${DASH_ID}"