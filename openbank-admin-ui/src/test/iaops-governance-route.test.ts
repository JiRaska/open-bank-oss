// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/governance/agentCharters', () => ({
  loadAgentCharters: vi.fn(),
}))

vi.mock('../lib/governance/aiGovernanceSnapshot', () => ({
  loadAiGovernanceSnapshot: vi.fn(),
}))

import { loadAgentCharters } from '../lib/governance/agentCharters'
import { loadAiGovernanceSnapshot } from '../lib/governance/aiGovernanceSnapshot'

describe('GET /api/iaops/governance', () => {
  afterEach(() => vi.restoreAllMocks())

  it('joins the generated rollout snapshot with the live charter registry', async () => {
    vi.mocked(loadAiGovernanceSnapshot).mockReturnValue({
      adrRef: 'ADR-0031',
      adrStatus: 'Accepted',
      phase: 2,
      totalPhases: 5,
      phaseLabel: 'Read-only oversight active — HITL proposal queue live; HolmesGPT + copilot deployed (proposal-only, no autonomous state-changing action)',
      agentsActing: 0,
      decisions: [{ id: 'D1', title: 'Agents as code (agents.yaml + charter)', status: 'built', detail: 'details' }],
      decisionSummary: { built: 1, partial: 0, planned: 0, total: 1 },
      compliance: [{ framework: 'GDPR', requirement: 'Art. 30', control: 'Masked', status: 'built' }],
      auditTrail: { capture: ['actorType=AI_AGENT'], pipeline: ['agent-service emits AuditEvent'], live: ['Hash-chain'], planned: ['By-actor live query endpoint (audit-service)'] },
      facts: { promptRegistryCoverage: { counts: { registered: 5 } } },
    })
    vi.mocked(loadAgentCharters).mockResolvedValue({
      available: true,
      defaults: { enforced: 'block', policy_decision: 'deny' },
      agents: [{ id: 'ui-assistant', plane: 'control', charter: 'chat', owns: [], skills: [], dataRead: [], pii: 'masked', toolsAllow: ['query.ledger.readonly'], toolsDeny: ['money.*'], requiresHuman: ['every: proposal'], tokensPerRun: 100000, runsPerDay: 500, schedule: null }],
      toolTiers: { read: ['query.ledger.readonly'] },
      runtime: { orchestration: 'temporal' },
      modelGateway: { gateway: 'litellm' },
    })

    const { GET } = await import('../app/api/iaops/governance/route')
    const res = await GET()
    expect(res.status).toBe(200)
    expect(res.headers.get('Cache-Control')).toBe('no-store')

    const body = await res.json()
    expect(body.phase).toBe(2)
    expect(body.phaseLabel).toContain('Read-only oversight active')
    expect(body.enforcement).toBe('block')
    expect(body.policyDefault).toBe('deny')
    expect(body.chartersAvailable).toBe(true)
    expect(body.agentCount).toBe(1)
    expect(body.agents[0].id).toBe('ui-assistant')
    expect(body.decisions).toEqual([{ id: 'D1', title: 'Agents as code (agents.yaml + charter)', status: 'built', detail: 'details' }])
    expect(body.facts.promptRegistryCoverage.counts.registered).toBe(5)
  })
})
