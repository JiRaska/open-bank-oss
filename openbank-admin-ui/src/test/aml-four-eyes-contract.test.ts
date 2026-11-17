// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { OUTCOME_LABELS_CS, describeFailure, outcomeLabel, outcomesFor, parseOutcomeCatalogue, parseProposal, parseReport } from '@/lib/aml/fourEyes'
import { parseAmlCase } from '@/lib/aml/amlCaseContract'

const t = (_cs: string, en: string) => en

describe('AML four-eyes client contract', () => {
  it('has a Czech label for every code aml-service can serve', () => {
    const file = join(__dirname, '../../../openbank-aml-service/src/main/resources/aml/decision-outcomes.provisional.json')
    const vocab = JSON.parse(readFileSync(file, 'utf8')) as { entries: { code: string }[] }
    expect(Object.keys(OUTCOME_LABELS_CS).sort()).toEqual(vocab.entries.map(e => e.code).sort())
  })

  it('takes the choosable set from the served vocabulary, including a code it has no translation for', () => {
    const served = parseOutcomeCatalogue({ entries: [
      { code: 'CONFIRMED_SUSPICION', label: 'Suspicion confirmed', meaning: 'm' },
      { code: 'FALSE_POSITIVE_DATA_QUALITY', label: 'False positive: data quality', meaning: 'm' },
      { code: 'NEW_CODE', label: 'Brand new', meaning: 'm' },
    ] })
    expect(outcomesFor('REPORT', served).map(o => o.code)).toEqual(['CONFIRMED_SUSPICION', 'NEW_CODE'])
    expect(outcomesFor('CLEAR', served).map(o => o.code)).toEqual(['FALSE_POSITIVE_DATA_QUALITY', 'NEW_CODE'])
    expect(outcomeLabel(served[2], 'NEW_CODE', 'cs')).toBe('Brand new')
    expect(outcomeLabel(served[0], 'CONFIRMED_SUSPICION', 'cs')).toBe('Podezření potvrzeno')
    expect(() => parseOutcomeCatalogue({ entries: [] })).toThrow()
  })

  it('parses proposals and reports and drops malformed rows', () => {
    const p = parseProposal({ proposalId: 'p1', caseId: 'c1', disposition: 'CLEAR', outcome: { code: 'FALSE_POSITIVE_DATA_QUALITY' }, state: 'PROPOSED', proposedBy: 'alice', proposedAt: '2026-11-13T00:00:00Z' })
    expect(p).toMatchObject({ outcomeCode: 'FALSE_POSITIVE_DATA_QUALITY', state: 'PROPOSED' })
    expect(parseProposal({ proposalId: 'p1' })).toBeNull()
    const r = parseReport({ reportId: 'r1', partyId: 'x', state: 'DRAFT', narrative: 'n', caseIds: ['c1'], draftedBy: 'alice', draftedAt: '2026-11-13T00:00:00Z', updatedAt: '2026-11-13T00:00:00Z' })
    expect(r?.caseIds).toEqual(['c1'])
    expect(parseReport({ ...r, state: 'SENT' })).toBeNull()
  })

  it('reads requiresFourEyesDisposition, defaulting to false for an older service', () => {
    const base = {
      id: '11111111-1111-4111-8111-111111111111', partyId: '22222222-2222-4222-8222-222222222222',
      customerReference: 'tm:1', screeningType: 'TRANSACTION_MONITORING', riskLevel: 'HIGH', status: 'OPEN',
      alertCode: 'X', screenedAt: '2026-11-01T00:00:00Z', createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z',
    }
    expect(parseAmlCase({ ...base, requiresFourEyesDisposition: true }).requiresFourEyesDisposition).toBe(true)
    expect(parseAmlCase(base).requiresFourEyesDisposition).toBe(false)
  })

  it('explains the maker-checker refusal and the role refusal differently', async () => {
    const mk = (status: number, message?: string) => new Response(JSON.stringify({ message }), { status })
    expect(await describeFailure(mk(422, 'approver is the maker'), t)).toContain('someone other than the maker')
    expect(await describeFailure(mk(403), t)).toContain('ROLE_COMPLIANCE')
    expect(await describeFailure(mk(409, 'changed'), t)).toContain('changed meanwhile')
  })
})
