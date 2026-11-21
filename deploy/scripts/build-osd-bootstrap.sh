#!/usr/bin/env bash
# Однократная подготовка bootstrap-образа OSD для сборки плагина chart_pilot_bridge.
# Результат: chartpilot/osd-bootstrap:3.10.0 (исходники OSD + yarn osd bootstrap).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OSD_VERSION="3.10.0"
SRC_IMAGE="chartpilot/osd-src:${OSD_VERSION}"
BOOTSTRAP_IMAGE="chartpilot/osd-bootstrap:${OSD_VERSION}"
CONTAINER="chartpilot-osd-bootstrap"

if docker image inspect "${BOOTSTRAP_IMAGE}" >/dev/null 2>&1; then
  echo "==> ${BOOTSTRAP_IMAGE} уже существует — пропуск (docker rmi ${BOOTSTRAP_IMAGE} для пересборки)."
  exit 0
fi

echo "==> Сборка базы с исходниками OSD ${OSD_VERSION} (~10 мин: tarball + git init)..."
docker build --network=host -t "${SRC_IMAGE}" \
  -f "${ROOT_DIR}/deploy/docker/osd-bootstrap.Dockerfile" \
  "${ROOT_DIR}/deploy/docker"

echo "==> yarn osd bootstrap в контейнере (живой лог: docker logs -f ${CONTAINER})..."
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
docker run -d --name "${CONTAINER}" --network=host \
  -e CYPRESS_INSTALL_BINARY=0 \
  -e PUPPETEER_SKIP_DOWNLOAD=true \
  -e PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true \
  "${SRC_IMAGE}" bash -c 'yarn config set network-timeout 600000 && yarn osd bootstrap'

echo "==> Ожидание завершения bootstrap (~5-10 мин)..."
EXIT_CODE="$(docker wait "${CONTAINER}")"
docker logs --tail 15 "${CONTAINER}"
if [[ "${EXIT_CODE}" != "0" ]]; then
  echo "Ошибка: bootstrap завершился с кодом ${EXIT_CODE} (полный лог: docker logs ${CONTAINER})" >&2
  exit 1
fi

echo "==> Commit образа ${BOOTSTRAP_IMAGE} (несколько минут, слой ~10 ГБ)..."
docker commit "${CONTAINER}" "${BOOTSTRAP_IMAGE}"
docker rm "${CONTAINER}" >/dev/null
echo "==> Готово: ${BOOTSTRAP_IMAGE}"