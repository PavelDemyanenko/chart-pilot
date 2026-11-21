/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

import { CoreSetup, CoreStart, Plugin, PluginInitializerContext } from '../../../src/core/public';
import { ChatPluginStartContract } from './chat_contract';
import { ChartPilotBridgePluginSetup, ChartPilotBridgePluginStart } from './types';
import { BridgeClient } from './services/bridge_client';
import { readBridgeUrl, readSessionId } from './services/session';
import { registerChartPilotChartAction } from './actions/chart_pilot_chart_action';

export class ChartPilotBridgePlugin
  implements
    Plugin<
      ChartPilotBridgePluginSetup,
      ChartPilotBridgePluginStart,
      Record<string, never>,
      { chat: ChatPluginStartContract }
    >
{
  private client?: BridgeClient;

  constructor(_initializerContext: PluginInitializerContext) {}

  public setup(core: CoreSetup): ChartPilotBridgePluginSetup {
    return {};
  }

  public start(core: CoreStart, deps: { chat: ChatPluginStartContract }): ChartPilotBridgePluginStart {
    // Богатые графики в чате доступны и без MCP-сессии: это расширение самого чата.
    registerChartPilotChartAction();

    const sessionId = readSessionId();
    if (!sessionId) {
      // Без sessionId плагин не вмешивается в обычную работу OpenSearch Dashboards.
      return {};
    }

    const bridgeUrl = readBridgeUrl();
    if (!bridgeUrl) {
      core.notifications.toasts.addDanger(
        'ChartPilot MCP: адрес моста (bridgeUrl) не прошёл проверку безопасности — подключение отклонено'
      );
      return {};
    }

    this.client = new BridgeClient({
      sessionId,
      bridgeUrl,
      notifications: core.notifications,
      http: core.http,
      chat: deps.chat,
    });
    this.client.start();
    return {};
  }

  public stop() {
    this.client?.stop();
    this.client = undefined;
  }
}