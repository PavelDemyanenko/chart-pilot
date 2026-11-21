/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

import { PluginInitializerContext } from '../../../src/core/public';
import { ChartPilotBridgePlugin } from './plugin';

export function plugin(initializerContext: PluginInitializerContext) {
  return new ChartPilotBridgePlugin(initializerContext);
}

export { ChartPilotBridgePluginSetup, ChartPilotBridgePluginStart } from './types';