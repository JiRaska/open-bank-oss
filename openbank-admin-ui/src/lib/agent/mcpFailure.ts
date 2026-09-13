import type { UnavailableKind } from '@/components/feedback/DataUnavailable'

export type AgentFailureKind = Extract<UnavailableKind, 'not_deployed' | 'unreachable' | 'unauthorized' | 'error'>

export class AgentCallError extends Error {
  constructor(readonly kind: AgentFailureKind) {
    super('Agent request failed')
    this.name = 'AgentCallError'
  }
}

export function classifyAgentFailure(status: number): AgentFailureKind {
  if (status === 401 || status === 403) return 'unauthorized'
  if (status === 404) return 'not_deployed'
  if (status === 408 || status === 502 || status === 503 || status === 504) return 'unreachable'
  return 'error'
}
