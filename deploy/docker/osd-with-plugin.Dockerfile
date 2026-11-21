# syntax=docker/dockerfile:1
# Сборка из корня репозитория (после подготовки bootstrap-образа, см. ниже):
#   docker build --network=host -t osd-chart-pilot:local -f deploy/docker/osd-with-plugin.Dockerfile .
# --network=host обязателен на WSL2: крупные TCP-загрузки через bridge-сеть docker0 зависают.
#
# Подготовка базового bootstrap-образа (однократно, ~10 мин):
#   ./deploy/scripts/build-osd-bootstrap.sh
# Скрипт собирает chartpilot/osd-bootstrap:3.10.0: исходники OSD 3.10.0 (tarball + git init),
# yarn osd bootstrap под непривилегированным пользователем, с пропуском бинарников
# cypress/puppeteer (нужны только для functional-тестов OSD, их CDN нестабилен).
# Bootstrap выполняется через docker run (а не RUN в BuildKit): на этой машине
# BuildKit-шаги с долгим сетевым/фоновым выводом зависают без диагностики.

ARG OSD_BOOTSTRAP_IMAGE=chartpilot/osd-bootstrap:3.10.0
FROM ${OSD_BOOTSTRAP_IMAGE} AS osd-source

FROM osd-source AS plugin-build
COPY --chown=1001:1001 osd-plugin/chart_pilot_bridge /osd/plugins/chart_pilot_bridge
WORKDIR /osd/plugins/chart_pilot_bridge
# Сторонние зависимости плагина (echarts) — в локальный node_modules, без правки root yarn.lock
RUN npm install --no-save --ignore-scripts --no-audit --no-fund
RUN ../../node_modules/.bin/tsc -b tsconfig.json
RUN yarn build

FROM opensearchproject/opensearch-dashboards:3.10.0
COPY --from=plugin-build --chown=1000:1000 /osd/plugins/chart_pilot_bridge/build/*.zip /tmp/chart_pilot_bridge.zip
RUN /usr/share/opensearch-dashboards/bin/opensearch-dashboards-plugin install file:///tmp/chart_pilot_bridge.zip \
    && rm -f /tmp/chart_pilot_bridge.zip
COPY deploy/docker/opensearch_dashboards.yml /usr/share/opensearch-dashboards/config/opensearch_dashboards.yml