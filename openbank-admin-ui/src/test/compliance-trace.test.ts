// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { buildComplianceTrace, computeVerdict, type TraceInputControl, type TraceInputs } from '../lib/compliance/trace'

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
    gateHealth: null,
    now: '2026-11-15T12:00:00.000Z',
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

  it('counts a test file as machine evidence', () => {
    const trace = buildComplianceTrace(inputs({
      controls: [{ id: 't', title: 't', category: 'x', frameworks: [], references: [], evidence: 'e', declaredStatus: 'enforced', evidenceRefs: [{ kind: 'test', ref: 'svc/src/test/T.kt' }] }],
    }))
    expect(trace.controls[0]).toMatchObject({ evidenceLevel: 'ci', verdict: 'enforced' })
  })
})

describe('compliance trace evidence age (ADR-0379 phase 2)', () => {
  const aged: TraceInputControl = {
    id: 'aged', title: 'Aged', category: 'x', frameworks: [], references: [], evidence: 'e',
    declaredStatus: 'enforced', evidenceRefs: [{ kind: 'gate', ref: 'g-enforced' }], maxEvidenceAgeDays: 14,
  }
  const health = (status: string, lastOkAt: string | null) => ({ collectedAt: '2026-11-15T11:00:00.000Z', gates: { 'g-enforced': { status, lastOkAt } } })

  it('keeps an age-bound claim whose gate passed within the window', () => {
    const c = buildComplianceTrace(inputs({ controls: [aged], gateHealth: health('ok', '2026-11-10T00:00:00.000Z') })).controls[0]
    expect(c).toMatchObject({ verdict: 'enforced', freshness: 'fresh', newestEvidenceAt: '2026-11-10T00:00:00.000Z' })
  })

  it('downgrades an age-bound claim whose newest pass is too old', () => {
    const c = buildComplianceTrace(inputs({ controls: [aged], gateHealth: health('ok', '2026-10-01T00:00:00.000Z') })).controls[0]
    expect(c).toMatchObject({ verdict: 'unverified', freshness: 'stale', downgradeReason: 'stale-evidence' })
  })

  it('does not let a recent pass vouch for a gate whose latest run failed', () => {
    const c = buildComplianceTrace(inputs({ controls: [aged], gateHealth: health('failed', '2026-11-14T00:00:00.000Z') })).controls[0]
    expect(c).toMatchObject({ verdict: 'unverified', freshness: 'unknown', downgradeReason: 'evidence-age-unknown' })
  })

  it('treats a missing gate-run snapshot as an unknown age, never as fresh', () => {
    const c = buildComplianceTrace(inputs({ controls: [aged], gateHealth: null })).controls[0]
    expect(c).toMatchObject({ verdict: 'unverified', downgradeReason: 'evidence-age-unknown' })
  })

  it('leaves a control without an age bound alone', () => {
    const c = buildComplianceTrace(inputs({ controls: [{ ...aged, maxEvidenceAgeDays: undefined }], gateHealth: null })).controls[0]
    expect(c).toMatchObject({ verdict: 'enforced', freshness: 'not-required' })
  })

  it('reports the evaluation instant and snapshot time it judged against', () => {
    const t = buildComplianceTrace(inputs({ gateHealth: health('ok', null) }))
    expect(t.asOf).toMatchObject({ evaluatedAt: '2026-11-15T12:00:00.000Z', gateHealthCollectedAt: '2026-11-15T11:00:00.000Z' })
  })
})
