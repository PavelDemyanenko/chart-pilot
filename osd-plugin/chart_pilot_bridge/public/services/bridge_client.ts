/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

import { CoreStart } from '../../../../src/core/public';
import { ChatPluginStartContract } from '../chat_contract';

interface BridgeClientDeps {
  sessionId: string;
  bridgeUrl: string;
  notifications: CoreStart['notifications'];
  http: CoreStart['http'];
  chat: ChatPluginStartContract;
}

interface CommandFrame {
  correlationId?: string;
  action?: string;
  payload?: { prompt?: string };
}

interface CommandResult {
  status: 'ok' | 'error';
  message: string;
  details?: Record<string, unknown>;
}

const MAX_RECONNECT_DELAY_MS = 10_000;
const INITIAL_RECONNECT_DELAY_MS = 1_000;

export class BridgeClient {
  private webSocket?: WebSocket;
  private stopped = false;
  private reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;
  private reconnectTimer?: ReturnType<typeof setTimeout>;
  private disconnectNotified = false;

  constructor(private readonly deps: BridgeClientDeps) {}

  public start(): void {
    this.connect();
  }

  public stop(): void {
    this.stopped = true;
    if (this.reconnectTimer !== undefined) {
      clearTimeout(this.reconnectTimer);
    }
    this.webSocket?.close();
    this.webSocket = undefined;
  }

  private connect(): void {
    if (this.stopped) {
      return;
    }
    const base = this.deps.bridgeUrl.replace(/\/+$/, '');
    const url = `${base}/ws/dashboard?sessionId=${encodeURIComponent(this.deps.sessionId)}`;

    let webSocket: WebSocket;
    try {
      webSocket = new WebSocket(url);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.webSocket = webSocket;

    webSocket.onopen = () => {
      this.reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;
      this.disconnectNotified = false;
      this.deps.notifications.toasts.addSuccess(
        `ChartPilot MCP подключён (сессия ${this.deps.sessionId})`
      );
    };

    webSocket.onmessage = (event: MessageEvent) => {
      void this.handleMessage(typeof event.data === 'string' ? event.data : '');
    };

    webSocket.onclose = () => {
      if (this.stopped) {
        return;
      }
      if (!this.disconnectNotified) {
        this.disconnectNotified = true;
        this.deps.notifications.toasts.addWarning(
          'ChartPilot MCP: соединение потеряно, переподключение в фоне…'
        );
      }
      this.scheduleReconnect();
    };

    webSocket.onerror = () => {
      webSocket.close();
    };
  }

  private scheduleReconnect(): void {
    if (this.stopped) {
      return;
    }
    this.reconnectTimer = setTimeout(() => this.connect(), this.reconnectDelayMs);
    this.reconnectDelayMs = Math.min(this.reconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS);
  }

  private async handleMessage(raw: string): Promise<void> {
    let frame: CommandFrame;
    try {
      frame = JSON.parse(raw) as CommandFrame;
    } catch {
      return;
    }
    const { correlationId, action } = frame;
    if (!correlationId || !action) {
      return;
    }

    try {
      const result = await this.execute(action, frame.payload);
      this.reply(correlationId, result);
    } catch (error) {
      this.reply(correlationId, {
        status: 'error',
        message: error instanceof Error ? error.message : String(error),
      });
    }
  }

  /**
   * Глобальный диапазон OSD живёт в _g URL и применяется любым приложением
   * (visualize/dashboard/discover); page context чата его отдаёт не везде.
   */
  private readTimeRange(): { from: string; to: string } | null {
    const match = window.location.href.match(/time:\(from:([^,)]+),to:([^)]+)\)/);
    if (match) {
      return { from: match[1], to: match[2] };
    }
    return this.deps.chat.chatService?.getCurrentTimeRange() ?? null;
  }

  private async execute(
    action: string,
    payload?: { prompt?: string }
  ): Promise<CommandResult> {
    if (action === 'GET_CONTEXT') {
      return {
        status: 'ok',
        message: 'Текущий контекст страницы OpenSearch Dashboards',
        details: {
          url: window.location.href,
          title: document.title,
          // активный временной диапазон страницы (CONTRIBUTING.md §2.3)
          timeRange: this.readTimeRange(),
        },
      };
    }

    if (action === 'RUN_AG_UI_PROMPT') {
      const prompt = payload?.prompt;
      if (!prompt) {
        return { status: 'error', message: 'RUN_AG_UI_PROMPT: пустой prompt' };
      }
      const chatService = this.deps.chat.chatService;
      if (!chatService) {
        return {
          status: 'error',
          message: 'Чат AG-UI недоступен: проверьте chat.enabled и chat.agUiUrl в opensearch_dashboards.yml',
        };
      }
      // При пустой истории чат не создаёт thread сам: sendMessage упадёт
      // с "Thread ID is required" — создаём тред явно (multi-turn сохраняется).
      if (!chatService.getThreadId()) {
        chatService.newThread();
      }
      // Чат OSD 3.10 требует выбранный data source, иначе уходит в режим
      // "Select a data source to continue..." и не отправляет сообщение.
      try {
        const found = (await this.deps.http.get('/api/saved_objects/_find', {
          query: { type: 'data-source', per_page: 1 },
        })) as { saved_objects?: Array<{ id?: string }> };
        const dataSourceId = found?.saved_objects?.[0]?.id;
        if (dataSourceId) {
          chatService.setDataSourceId(dataSourceId);
        }
      } catch {
        // без data source чат может запросить выбор вручную — не падаем
      }
      await chatService.openWindow();
      // Без clearConversation: сохраняется threadId и история диалога (multi-turn UX).
      const baseline = document.body.innerText.length;
      await chatService.sendMessageWithWindow(prompt, []);
      // the tool contract: подтверждение возвращается ПОСЛЕ отрисовки. sendMessageWithWindow
      // резолвится на отправке (observable-заглушка), поэтому завершение run наблюдаем
      // по сигналам UI чата: input возвращается после стриминга, ответ появился в DOM.
      const outcome = await this.waitForRunCompletion(baseline);
      if (outcome === 'error') {
        return {
          status: 'error',
          message: 'AG-UI run завершился с ошибкой (детали в панели чата)',
          details: { prompt, runStatus: 'error' },
        };
      }
      if (outcome === 'timeout') {
        return {
          status: 'error',
          message: 'AG-UI run не завершился за 55 секунд (детали в панели чата)',
          details: { prompt, runStatus: 'timeout' },
        };
      }
      return {
        status: 'ok',
        message: 'Промпт отправлен в AG-UI чат, run завершён, график/ответ в панели чата',
        details: { prompt, runStatus: 'finished' },
      };
    }

    return { status: 'error', message: `Неизвестное действие: ${action}` };
  }

  private async waitForRunCompletion(baselineLength: number): Promise<'finished' | 'error' | 'timeout'> {
    for (let i = 0; i < 110; i++) {
      await new Promise((resolve) => setTimeout(resolve, 500));
      const body = document.body.innerText;
      const fresh = body.slice(baselineLength);
      if (/run failed|Access denied|HTTP [45]\d\d/.test(fresh)) {
        return 'error';
      }
      const input = document.querySelector<HTMLTextAreaElement>(
        "textarea[placeholder='How can I help you today?']"
      );
      const idle = input !== null && input.offsetParent !== null;
      if (idle && fresh.length > 20) {
        return 'finished';
      }
    }
    return 'timeout';
  }

  private reply(correlationId: string, result: CommandResult): void {
    if (this.webSocket?.readyState === WebSocket.OPEN) {
      this.webSocket.send(JSON.stringify({ correlationId, result }));
    }
  }
}