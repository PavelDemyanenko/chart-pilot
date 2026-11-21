/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Структурный контракт start-фазы плагина `chat` (OSD 3.10.0).
 * Описан локально, чтобы не тянуть исходники chat-плагина в граф типов:
 * его исходники не проходят tsc под конфигурацией внешнего плагина.
 * Runtime-зависимость объявлена в opensearch_dashboards.json (requiredPlugins: ["chat"]).
 * Источник контракта: src/plugins/chat/public/types.ts и services/chat_service.ts (тег 3.10.0).
 */
export interface ChatServiceContract {
  openWindow(): Promise<unknown>;
  getThreadId(): string | null | undefined;
  newThread(): void;
  setDataSourceId(id: string): void;
  getCurrentTimeRange(): { from: string; to: string } | undefined;
  sendMessageWithWindow(
    content: string,
    messages: unknown[],
    options?: { clearConversation?: boolean; dataSourceId?: string }
  ): Promise<unknown>;
}

export interface ChatPluginStartContract {
  chatService: ChatServiceContract | undefined;
}