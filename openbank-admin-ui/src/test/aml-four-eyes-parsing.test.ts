// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { describeFailure, outcomeLabel, parseList, parseOutcomeCatalogue, parseProposal, parseReport } from '@/lib/aml/fourEyes'
import { parseAmlCase, parseAmlCases } from '@/lib/aml/amlCaseContract'

const t = (_cs: string, en: string) => en
const res = (status: number, body?: unknown) =>
  new Response(body === undefined ? 'not json' : JSON.stringify(body), { status })

const report = {
  reportId: 'r1', partyId: 'p1', state: 'FILED', narrative: 'Structured cash deposits', caseIds: ['c1', 7, 'c2'],
  draftedBy: 'alice', draftedAt: '2026-11-10T00:00:00Z', approvedBy: 'bob', approvedAt: '2026-11-11T00:00:00Z',
  filingReference: 'FAU-2026-0042', filedAt: '2026-11-12T00:00:00Z', withdrawnBy: null, withdrawnAt: null,
  outcome: { code: 'CONFIRMED_SUSPICION' }, updatedAt: '2026-11-12T00:00:00Z',
}

describe('suspicious-transaction report parsing', () => {
  it('keeps every field the officer sees and drops non-string case ids', () => {
    expect(parseReport(report)).toEqual({
      reportId: 'r1', partyId: 'p1', state: 'FILED', narrative: 'Structured cash deposits', caseIds: ['c1', 'c2'],
      draftedBy: 'alice', draftedAt: '2026-11-10T00:00:00Z', approvedBy: 'bob', approvedAt: '2026-11-11T00:00:00Z',
      filingReference: 'FAU-2026-0042', filedAt: '2026-11-12T00:00:00Z', withdrawnBy: null, withdrawnAt: null,
      outcomeCode: 'CONFIRMED_SUSPICION', updatedAt: '2026-11-12T00:00:00Z',
    })
  })

  it.each(['reportId', 'partyId', 'narrative', 'draftedBy', 'draftedAt', 'updatedAt', 'state'])(
    'refuses a report without %s', field => {
      expect(parseReport({ ...report, [field]: '' })).toBeNull()
    })

  it('accepts every report state and reads a missing case list as empty', () => {
    for (const state of ['DRAFT', 'APPROVED', 'FILED', 'WITHDRAWN']) expect(parseReport({ ...report, state })?.state).toBe(state)
    expect(parseReport({ ...report, caseIds: 'c1' })?.caseIds).toEqual([])
    expect(parseReport({ ...report, outcome: null })?.outcomeCode).toBeNull()
    expect(parseReport(null)).toBeNull()
  })
})

describe('disposition proposal parsing', () => {
  const proposal = {
    proposalId: 'p', caseId: 'c', disposition: 'REPORT', outcome: { code: 'CONFIRMED_SUSPICION' }, note: ' ',
    state: 'APPROVED', proposedBy: 'alice', proposedAt: '2026-11-12T00:00:00Z', decidedBy: 'bob',
    decidedAt: '2026-11-12T01:00:00Z', decisionReason: 'Agreed',
  }
  it('keeps who proposed and who decided', () => {
    expect(parseProposal(proposal)).toMatchObject({ note: null, decidedBy: 'bob', decisionReason: 'Agreed', disposition: 'REPORT' })
  })
  it.each(['proposalId', 'caseId', 'disposition', 'state', 'proposedBy', 'proposedAt'])('refuses one without %s', field => {
    expect(parseProposal({ ...proposal, [field]: null })).toBeNull()
  })
  it('refuses one without an outcome code', () => {
    expect(parseProposal({ ...proposal, outcome: {} })).toBeNull()
    expect(parseProposal([])).toBeNull()
  })
  it('lists only valid rows and treats a non-array as empty', () => {
    expect(parseList([proposal, { proposalId: 'x' }], parseProposal)).toHaveLength(1)
    expect(parseList({ items: [] }, parseProposal)).toEqual([])
  })
})

describe('outcome vocabulary', () => {
  it('drops malformed entries, keeps meaning optional, refuses a non-object', () => {
    expect(parseOutcomeCatalogue({ entries: [{ code: 'A', label: 'a' }, { code: 'B' }, 'x'] })).toEqual([{ code: 'A', label: 'a', meaning: '' }])
    expect(() => parseOutcomeCatalogue([])).toThrow('Invalid outcome vocabulary')
    expect(() => parseOutcomeCatalogue({ entries: [{ code: 'B' }] })).toThrow('Empty outcome vocabulary')
  })
  it('labels in English from the service, in Czech from the translation, and a missing code as a dash', () => {
    expect(outcomeLabel({ code: 'CONFIRMED_SUSPICION', label: 'Suspicion confirmed', meaning: '' }, 'CONFIRMED_SUSPICION', 'en')).toBe('Suspicion confirmed')
    expect(outcomeLabel(undefined, 'UNKNOWN_CODE', 'cs')).toBe('UNKNOWN_CODE')
    expect(outcomeLabel(undefined, null, 'en')).toBe('—')
  })
})

describe('describeFailure', () => {
  it('passes the service message through for 422, 409 and 400, and names the role for 403', async () => {
    expect(await describeFailure(res(422, { message: 'maker is checker' }), t)).toContain('(maker is checker)')
    expect(await describeFailure(res(409, { error: 'changed' }), t)).toContain('meanwhile: changed')
    expect(await describeFailure(res(400, { message: 'outcomeCode is required' }), t)).toContain('Invalid request: outcomeCode is required')
    expect(await describeFailure(res(403, { message: 'x' }), t)).toContain('ROLE_COMPLIANCE')
  })
  it('still answers without a body, without leaking a raw status', async () => {
    expect(await describeFailure(res(422), t)).not.toContain('(')
    expect(await describeFailure(res(409), t)).not.toContain(': ')
    expect(await describeFailure(res(400), t)).toBe('Invalid request')
    expect(await describeFailure(res(500, { message: 'stack trace' }), t)).not.toContain('stack trace')
  })
})

describe('AML case contract edges', () => {
  const base = {
    id: '11111111-1111-4111-8111-111111111111', partyId: '22222222-2222-4222-8222-222222222222',
    customerReference: 'onboarding-1', screeningType: 'CUSTOMER_ONBOARDING', riskLevel: 'LOW', status: 'OPEN',
    alertCode: 'X', screenedAt: '2026-11-01T00:00:00Z', createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z',
  }
  it('refuses a non-object row, a non-array register and an unparseable instant', () => {
    expect(() => parseAmlCase([base])).toThrow('Invalid AML case')
    expect(() => parseAmlCases({})).toThrow('Invalid AML cases response')
    expect(() => parseAmlCase({ ...base, screenedAt: 'yesterday' })).toThrow('screenedAt')
    expect(() => parseAmlCase({ ...base, decidedAt: 'soon' })).toThrow('decidedAt')
    expect(parseAmlCase({ ...base, decidedAt: '2026-11-02T00:00:00Z' }).decidedAt).toBe('2026-11-02T00:00:00Z')
  })
})
