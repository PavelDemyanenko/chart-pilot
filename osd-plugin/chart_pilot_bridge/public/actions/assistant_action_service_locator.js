/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

// Runtime-мост к context_provider: rspack разрешает импорт чужого плагина только
// через его public index, а index не проходит strict tsc (OSD собирает babel'ом).
// Поэтому tsc видит только соседний .d.ts-контракт, а rspack — этот .js.
import { AssistantActionService } from '../../../../src/plugins/context_provider/public';

export function getAssistantActionService() {
  return AssistantActionService.getInstance();
}