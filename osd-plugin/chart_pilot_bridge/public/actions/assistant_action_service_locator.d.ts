/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

import type { ReactNode } from 'react';

export type ChartPilotToolStatus = 'pending' | 'executing' | 'complete' | 'failed';

export interface ChartPilotRenderProps {
  status: ChartPilotToolStatus;
  args?: any;
  result?: any;
  error?: Error;
  toolCallId?: string;
}

export interface ChartPilotAssistantAction {
  name: string;
  description: string;
  parameters: {
    type: 'object';
    properties: Record<string, any>;
    required: string[];
  };
  handler?: (args: any, toolCallId?: string) => Promise<any>;
  render?: (props: ChartPilotRenderProps) => ReactNode;
  useCustomRenderer?: boolean;
  renderPlacement?: 'above' | 'below';
  requiresConfirmation?: boolean;
}

export interface ChartPilotActionService {
  registerAction(action: ChartPilotAssistantAction): void;
  unregisterAction(name: string): void;
}

export function getAssistantActionService(): ChartPilotActionService;