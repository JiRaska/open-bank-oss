// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { buildComplianceTrace, computeVerdict, type TraceInputs } from '../lib/compliance/trace'

function inputs(overrides: Partial<TraceInputs> = {}): TraceInputs {
  return {
    frameworks: [{ id: 'DORA', name: 'DORA' }],
    controls: [
      { id: 'gated', title: 'Gated', category: 'x', frameworks: ['DORA'], references: [], evidence: 'e', declaredStatus: 'enforced', evidenceRefs: [{ kind: 'gate', ref: 'g-enforced' }] },
      { id: 'adr-only', title: 'ADR only', category: 'x', frameworks: ['DORA'], references: [], evidence: 'e', declaredStatus: 'enforced', evidenceRefs: [{ kind: 'adr', ref: '0001' }] },
      { id: 'advisory', title: 'Advisory', category: 'x', frameworks: ['DORA'], references: [], evidence: 'e', declaredStatus: 'partial', evidenceRefs: [{ kind: 'gate', ref: 'g-advisory' }] },
      { id: 'planned', title: 'Planned', category: 'x', frameworks: ['DORA'], references: [], evidence: 'e', declaredStatus: 'planned', evidenceRefs: [] },
    ],
    obligations: [
      { id: 'o1', area: 'ict', name: { cs: 'a', en: 'a' }, authority: 'cnb', legalBasis: 'x', cadence: 'x', cadenceConfidence: 'verified', coverage: { status: 'missing' }, controls: ['gated', 'ghost'] },
      { id: 'o2', area: 'ict', name: { cs: 'b', en: 'b' }, authority: 'cnb', legalBasis: 'x', cadence: 'x', cadenceConfidence: 'verified', coverage: { status: 'filed-return' } },
    ],
    areas: [],
    gates: [
      { id: 'g-enforced', name: 'Enforced gate', mode: 'enforced', line: 10 },
      { id: 'g-advisory', name: 'Advisory gate', mode: 'advisory', line: 20 },
    ],
    commit: 'a'.repeat(40),
    postureCollectedAt: null,
    adrSlugs: { '0001': '0001-record' },
    ...overrides,
  }
}

describe('compliance trace verdict (ADR-0379)', () => {
  it('keeps a positive claim only when a gate or workflow backs it', () => {
    const trace = buildComplianceTrace(inputs())
    const byId = Object.fromEntries(trace.controls.map(c => [c.id, c]))
    expect(byId.gated.verdict).toBe('enforced')
    expect(byId.gated.evidenceLevel).toBe('enforced-gate')
    expect(byId['adr-only']).toMatchObject({ verdict: 'unverified', downgradeReason: 'no-machine-evidence', evidenceLevel: 'documented' })
    expect(byId.advisory).toMatchObject({ verdict: 'partial', evidenceLevel: 'ci' })
    expect(byId.planned.verdict).toBe('planned')
  })

  it('downgrades every positive claim when the gate catalogue is absent', () => {
    const trace = buildComplianceTrace(inputs({ gates: [] }))
    expect(trace.controls.find(c => c.id === 'gated')).toMatchObject({ verdict: 'unverified' })
    expect(trace.controls.find(c => c.id === 'gated')?.evidenceRefs[0].gateMode).toBe('missing')
    expect(trace.summary.machineEvidenced).toBe(0)
  })

  it('treats an unknown declared status as unverified, never as passing', () => {
    expect(computeVerdict('unknown', 'enforced-gate').verdict).toBe('unverified')
  })

  it('joins obligations to controls both ways and surfaces an unknown control id', () => {
    const trace = buildComplianceTrace(inputs())
    const o1 = trace.obligations.find(o => o.id === 'o1')!
    expect(o1).toMatchObject({ controls: ['gated'], unknownControls: ['ghost'], hasControl: true })
    expect(trace.controls.find(c => c.id === 'gated')?.obligationIds).toEqual(['o1'])
    expect(trace.summary).toMatchObject({ obligations: 2, obligationsWithControl: 1, obligationsByCoverage: { missing: 1, 'filed-return': 1 } })
  })

  it('links evidence to the commit the image was built from, and ADRs to the in-app page', () => {
    const refs = buildComplianceTrace(inputs()).controls.flatMap(c => c.evidenceRefs)
    expect(refs.find(r => r.ref === 'g-enforced')?.href).toBe(`https://github.com/JiRaska/open-bank-oss/blob/${'a'.repeat(40)}/.github/gates/gates.yaml#L10`)
    expect(refs.find(r => r.kind === 'adr')?.href).toBe('/docs/adr/0001-record')
  })

  it('drops an evidence kind it does not know instead of counting it as evidence', () => {
    const trace = buildComplianceTrace(inputs({
      controls: [{ id: 'x', title: 'x', category: 'x', frameworks: [], references: [], evidence: 'e', declaredStatus: 'enforced', evidenceRefs: [{ kind: 'vibes', ref: 'trust me' }] }],
    }))
    expect(trace.controls[0]).toMatchObject({ evidenceRefs: [], evidenceLevel: 'none', verdict: 'unverified' })
  })
})
