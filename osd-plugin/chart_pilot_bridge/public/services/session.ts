/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

const SESSION_ID_KEY = 'chart_pilot_session_id';
const BRIDGE_URL_KEY = 'chart_pilot_bridge_url';

// SEC-01 (компромисс аудита §7 для Minikube без Ingress): bridgeUrl принимается
// только для локальных/приватных адресов — фишинговый wss://evil.com отвергается.
const LOCAL_BRIDGE_PATTERN =
  /^wss?:\/\/(localhost|127\.0\.0\.1|192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})(:\d{1,5})?$/;

function queryParam(name: string): string | null {
  return new URLSearchParams(window.location.search).get(name);
}

export function readSessionId(): string | null {
  const fromUrl = queryParam('sessionId');
  if (fromUrl) {
    window.sessionStorage.setItem(SESSION_ID_KEY, fromUrl);
    return fromUrl;
  }
  return window.sessionStorage.getItem(SESSION_ID_KEY);
}

export function readBridgeUrl(): string | null {
  const fromUrl = queryParam('bridgeUrl');
  const candidate =
    fromUrl ??
    window.sessionStorage.getItem(BRIDGE_URL_KEY) ??
    `ws://${window.location.hostname}:8080`;
  if (!LOCAL_BRIDGE_PATTERN.test(candidate)) {
    return null;
  }
  if (fromUrl) {
    window.sessionStorage.setItem(BRIDGE_URL_KEY, fromUrl);
  }
  return candidate;
}