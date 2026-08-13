// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AgentBodyAnalysis, AgentMeshMap } from '@/components/agent/AgentDiagnostics'
import {
  deriveAgentDiagnostics,
  deriveAgentMesh,
} from '@/lib/governance/agentDiagnostics'
import type { AgentCharter, AgentCharterRegistry } from '@/lib/governance/agentCharters'

function charter(id: string, overrides: Partial<AgentCharter> = {}): AgentCharter {
  return {
    id,
    plane: 'control',
    charter: 'test charter',
    owns: [],
    skills: [],
    dataRead: [],
    pii: 'masked',
    toolsAllow: [],
    toolsDeny: [],
    requiresHuman: [],
    tokensPerRun: 0,
    runsPerDay: 0,
    caseCapabilities: [],
    schedule: null,
    ...overrides,
  }
}

function registry(agents: AgentCharter[]): AgentCharterRegistry {
  return {
    available: true,
    defaults: {},
    agents,
    toolTiers: {},
    runtime: {},
    modelGateway: {},
    caseClasses: ['incident-response', 'aml-alert'],
  }
}

describe('agent operating diagnostics', () => {
  it('derives relative bars from governed charter facts rather than invented ratings', () => {
    const selected = charter('finops-agent', {
      dataRead: ['costs', 'usage'],
      toolsAllow: ['read.costs'],
      toolsDeny: ['money.*'],
      requiresHuman: ['every: proposal'],
      tokensPerRun: 50_000,
      runsPerDay: 12,
    })
    const largest = charter('devops-agent', {
      dataRead: ['a', 'b', 'c', 'd'],
      toolsAllow: ['a', 'b'],
      toolsDeny: ['a', 'b'],
      requiresHuman: ['a', 'b'],
      tokensPerRun: 100_000,
      runsPerDay: 24,
    })

    const diagnostics = deriveAgentDiagnostics(selected, [selected, largest])

    expect(diagnostics.find(item => item.key === 'data')).toMatchObject({ value: 2, fleetMax: 4, percent: 50 })
    expect(diagnostics.find(item => item.key === 'guardrails')).toMatchObject({ value: 2, fleetMax: 4, percent: 50 })
    expect(diagnostics.find(item => item.key === 'tokens')).toMatchObject({ value: 50_000, fleetMax: 100_000, percent: 50 })
  })

  it('reports the mesh as a foundation until a specialist has join or contribute capability', () => {
    const selected = charter('finops-agent')
    const coordinator = charter('case-coordinator', {
      caseCapabilities: ['case.open', 'case.coordinate', 'case.synthesize'],
      requiresHuman: ['every: proposal'],
    })
    const foundation = deriveAgentMesh(selected, registry([selected, coordinator]))
    expect(foundation).toMatchObject({
      coordinatorId: 'case-coordinator',
      charteredParticipantIds: [],
      state: 'foundation',
      synthesisEnabled: true,
      humanGateEnabled: true,
    })

    const participant = charter('rca-investigator', { caseCapabilities: ['case.join', 'case.contribute'] })
    expect(deriveAgentMesh(selected, registry([selected, coordinator, participant]))).toMatchObject({
      charteredParticipantIds: ['rca-investigator'],
      state: 'chartered',
    })
  })

  it('renders accessible operating meters and names the current mesh limitation', () => {
    const selected = charter('finops-agent', { dataRead: ['costs'], toolsAllow: ['read.costs'] })
    const coordinator = charter('case-coordinator', {
      caseCapabilities: ['case.open', 'case.coordinate', 'case.synthesize'],
      requiresHuman: ['every: proposal'],
    })
    const fleet = registry([selected, coordinator])
    const diagnostics = deriveAgentDiagnostics(selected, fleet.agents)
    const mesh = deriveAgentMesh(selected, fleet)

    render(
      <>
        <AgentBodyAnalysis agentId="finops-agent" diagnostics={diagnostics} language="en" />
        <AgentMeshMap agentId="finops-agent" mesh={mesh} language="en" />
      </>,
    )

    expect(screen.getAllByRole('progressbar')).toHaveLength(6)
    expect(screen.getByText('Not an intelligence or quality score')).toBeInTheDocument()
    expect(screen.getByText(/0 of 2: capability not granted yet/)).toBeInTheDocument()
    expect(screen.getByText(/multi-agent mesh activates only after specialists receive/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open live case threads/ })).toHaveAttribute('href', '/iaops/cases')
    expect(screen.getByRole('list', { name: /Flow from signal through coordinator/ })).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(5)
  })

  it('does not present chartered participants or case classes as runtime-enabled', () => {
    const selected = charter('finops-agent')
    const coordinator = charter('case-coordinator', {
      caseCapabilities: ['case.open', 'case.coordinate', 'case.synthesize'],
      requiresHuman: ['every: proposal'],
    })
    const participant = charter('rca-investigator', { caseCapabilities: ['case.join'] })
    const mesh = deriveAgentMesh(selected, registry([selected, coordinator, participant]))

    render(<AgentMeshMap agentId="finops-agent" mesh={mesh} language="en" />)

    expect(screen.getByText('1 chartered specialist')).toBeInTheDocument()
    expect(screen.getByText(/Actual runtime admission is controlled by the coordinator allowlist/)).toBeInTheDocument()
    expect(screen.getByText('Declared classes (not runtime state)')).toBeInTheDocument()
    expect(screen.queryByText(/connected specialists/i)).not.toBeInTheDocument()
  })
})
