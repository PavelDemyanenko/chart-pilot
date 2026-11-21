# syntax=docker/dockerfile:1
# База для bootstrap OSD: исходники + инструменты, БЕЗ самого bootstrap
# (bootstrap выполняется через docker run в build-osd-bootstrap.sh —
# BuildKit-шаги с долгим выводом зависают на этой машине без диагностики).
FROM node:22.23.4-bookworm
RUN apt-get update \
    && apt-get install -y --no-install-recommends git python3 make g++ ca-certificates \
    && rm -rf /var/lib/apt/lists/*
# Tarball вместо git clone: shallow-clone огромного репозитория OSD зависает
# на серверной подготовке pack'а; потоковая загрузка tarball работает стабильно.
RUN mkdir -p /osd \
    && curl -fsSL https://codeload.github.com/opensearch-project/OpenSearch-Dashboards/tar.gz/refs/tags/3.10.0 \
       | tar -xz -C /osd --strip-components=1
WORKDIR /osd
# @osd/pm на bootstrap вызывает git ls-files — tarball не содержит .git.
RUN git init -q \
    && git add -A \
    && git -c user.email=build@chartpilot.local -c user.name=chartpilot-build commit -qm "OpenSearch Dashboards 3.10.0 source"
# Скрипты OSD отказываются работать под root.
RUN useradd -m -u 1001 builder && chown -R 1001:1001 /osd
USER 1001
# Cypress/puppeteer нужны только для functional-тестов OSD; бинарники не качаем
# (CDN Google/Cypress нестабилен из этой сети и вешает postinstall).
ENV CYPRESS_INSTALL_BINARY=0
ENV PUPPETEER_SKIP_DOWNLOAD=true
ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true