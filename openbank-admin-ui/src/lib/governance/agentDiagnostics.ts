// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import type { AgentCharter, AgentCharterRegistry } from './agentCharters'

export type AgentDiagnosticKey = 'data' | 'tools' | 'guardrails' | 'domain' | 'tokens' | 'cadence'

export interface AgentDiagnostic {
  key: AgentDiagnosticKey
  value: number
  fleetMax: number
  percent: number
}

export interface AgentMeshSummary {
  coordinatorId: string | null
  selectedCapabilities: string[]
  charteredParticipantIds: string[]
  declaredCaseClasses: string[]
  totalAgents: number
  synthesisEnabled: boolean
  humanGateEnabled: boolean
  state: 'foundation' | 'chartered'
}

interface Measure {
  key: AgentDiagnosticKey
  value: (agent: AgentCharter) => number
}

const MEASURES: Measure[] = [
  { key: 'data', value: agent => agent.dataRead.length },
  { key: 'tools', value: agent => agent.toolsAllow.length },
  { key: 'guardrails', value: agent => agent.toolsDeny.length + agent.requiresHuman.length },
  { key: 'domain', value: agent => agent.owns.length + agent.skills.length },
  { key: 'tokens', value: agent => agent.tokensPerRun ?? 0 },
  { key: 'cadence', value: agent => agent.runsPerDay ?? 0 },
]

/**
 * Builds a relative operating envelope from charter facts. Percentages compare the selected
 * agent with the largest declaration in the current registry; they are not quality, accuracy,
 * autonomy or model-performance scores.
 */
export function deriveAgentDiagnostics(agent: AgentCharter, fleet: AgentCharter[]): AgentDiagnostic[] {
  return MEASURES.map(measure => {
    const value = measure.value(agent)
    const fleetMax = fleet.reduce((max, candidate) => Math.max(max, measure.value(candidate)), 0)
    return {
      key: measure.key,
      value,
      fleetMax,
      percent: fleetMax === 0 ? 0 : Math.round((value / fleetMax) * 100),
    }
  })
}

/** Derives the honest current mesh posture from case capabilities declared in agents.yaml. */
export function deriveAgentMesh(agent: AgentCharter, registry: AgentCharterRegistry): AgentMeshSummary {
  const coordinator = registry.agents.find(candidate => candidate.caseCapabilities.includes('case.coordinate')) ?? null
  const participants = registry.agents.filter(candidate =>
    candidate.caseCapabilities.includes('case.join') || candidate.caseCapabilities.includes('case.contribute'))

  return {
    coordinatorId: coordinator?.id ?? null,
    selectedCapabilities: agent.caseCapabilities,
    charteredParticipantIds: participants.map(candidate => candidate.id),
    declaredCaseClasses: registry.caseClasses,
    totalAgents: registry.agents.length,
    synthesisEnabled: coordinator?.caseCapabilities.includes('case.synthesize') ?? false,
    humanGateEnabled: (coordinator?.requiresHuman.length ?? 0) > 0,
    // agents.yaml proves charter intent only. Runtime admission remains separately enforced by
    // CaseCoordinatorConfig.case().swarmAgents(), which this admin BFF cannot observe.
    state: participants.length > 0 ? 'chartered' : 'foundation',
  }
}
