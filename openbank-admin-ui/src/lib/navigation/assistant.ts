// SPDX-License-Identifier: Apache-2.0
export const ASSISTANT_REQUEST = 'openbank:assistant-request'
export type AssistantContext = { label: string; route: string }
export type AssistantRequest = { prompt: string; context: AssistantContext }
export function openAssistant(request: AssistantRequest) {
  window.dispatchEvent(new CustomEvent<AssistantRequest>(ASSISTANT_REQUEST, { detail: request }))
}
