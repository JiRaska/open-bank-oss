// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0344 D7 corrections panel: the proposal carries the changes, reason, effective date and the
// limit-override request; a PENDING correction shows its preview (changed flows, delta journals);
// approve / reject / override are hidden from term authors and AI principals.
import { act, cleanup, fireEvent, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CorrectionsPanel } from '@/components/treasury/wb/CorrectionsPanel'
import { canDecideCorrection } from '@/components/treasury/wb/payments'
import { bodyOf, json, renderPage, type Call } from './treasury-wb-harness'

let calls: Call[] = []
let router: (url: string, init?: RequestInit) => Response

const pending = {
  correctionId: 'c-1', dealId: 'd-1', status: 'PENDING', baseVersion: 1, newVersion: null, fullUnwind: false,
  reason: 'rate captured wrong', effectiveFrom: '2026-10-19', proposedBy: 'bert.back', proposedAt: '2026-10-19T09:00:00Z',
  limitOverrideRequested: true, limitOverrideBy: null,
  preview: {
    changedFlows: [{ slot: '1/0', before: { legIndex: 1, periodIndex: 0, kind: 'INTEREST', paymentDate: '2026-11-02', amount: 100, currency: 'CZK' }, after: { legIndex: 1, periodIndex: 0, kind: 'INTEREST', paymentDate: '2026-11-02', amount: 120, currency: 'CZK' } }],
    deltaJournals: [{ sourceEvent: 'ACCRUAL', key: 'k-1', valueDate: '2026-10-19', lines: [{ accountingClass: 'X', role: 'ACCRUED_INTEREST', side: 'DEBIT', amount: 20, currency: 'CZK' }, { accountingClass: 'X', role: 'INTEREST_INCOME', side: 'CREDIT', amount: 20, currency: 'CZK' }] }],
  },
}

beforeEach(() => {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (u: string, init?: RequestInit) => { calls.push({ url: String(u), init }); return router(String(u), init) }))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

const deal = { dealId: 'd-1', createdBy: 'dana.dealer', state: 'BOOKED' }

describe('corrections panel', () => {
  it('back office proposes with the reason, date, changes and a limit-override request', async () => {
    router = (url, init) => (init?.method === 'POST' ? json(pending, 201) : json([]))
    await renderPage(<CorrectionsPanel deal={deal} actor="bert.back" roles={['ROLE_TREASURY_BACK_OFFICE']} onChanged={() => {}} />)
    await act(async () => { fireEvent.change(screen.getByLabelText(/Changed terms|Změny podmínek/), { target: { value: '{"rate": 4.1}' } }) })
    await act(async () => { fireEvent.change(screen.getByLabelText(/Correction reason|Důvod opravy/), { target: { value: 'rate captured wrong' } }) })
    await act(async () => { fireEvent.change(screen.getByLabelText(/Effective from|Účinnost od/), { target: { value: '2026-10-19' } }) })
    await act(async () => { fireEvent.click(screen.getByLabelText(/Request a limit override|Žádat o překročení/)) })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /^(Propose correction|Navrhnout opravu)$/ })) })
    const post = calls.find(c => c.init?.method === 'POST')!
    expect(post.url).toMatch(/\/deals\/d-1\/corrections$/)
    expect(bodyOf(post)).toMatchObject({ changes: { rate: 4.1 }, reason: 'rate captured wrong', effectiveFrom: '2026-10-19', requestLimitOverride: true, fullUnwind: false })
  })

  it('shows the preview diff and delta journals; an independent approver approves', async () => {
    router = (url, init) => (init?.method === 'POST' ? json({ ...pending, status: 'APPROVED' }) : json([pending]))
    await renderPage(<CorrectionsPanel deal={deal} actor="olga.approver" roles={['ROLE_TREASURY_APPROVER']} onChanged={() => {}} />)
    expect((await screen.findByTestId('correction-flows')).textContent).toMatch(/1\/0/)
    expect(screen.getByTestId('correction-deltas').textContent).toMatch(/Dr ACCRUED_INTEREST 20\.00 CZK, Cr INTEREST_INCOME 20\.00 CZK/)
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /^(Approve correction|Schválit opravu)$/ })) })
    expect(calls.some(c => c.url.endsWith('/corrections/c-1/approve') && c.init?.method === 'POST')).toBe(true)
  })

  it('hides decisions from the proposer, the deal creator and an AI principal; override only for a senior', async () => {
    router = () => json([pending])
    for (const [actor, roles] of [['bert.back', ['ROLE_TREASURY_APPROVER']], ['dana.dealer', ['ROLE_TREASURY_APPROVER']], ['olga.approver', ['ROLE_TREASURY_APPROVER', 'ROLE_AI_AGENT']]] as const) {
      await renderPage(<CorrectionsPanel deal={deal} actor={actor} roles={[...roles]} onChanged={() => {}} />)
      await screen.findByTestId('correction')
      expect(screen.queryByRole('button', { name: /^(Approve correction|Schválit opravu)$/ })).toBeNull()
      expect(screen.queryByRole('button', { name: /^(Reject correction|Zamítnout opravu)$/ })).toBeNull()
      cleanup()
    }
    await renderPage(<CorrectionsPanel deal={deal} actor="sam.senior" roles={['ROLE_TREASURY_SENIOR_APPROVER']} onChanged={() => {}} />)
    await screen.findByTestId('correction')
    expect(screen.getByRole('button', { name: /^(Override the limit|Povolit překročení limitu)$/ })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /^(Propose correction|Navrhnout opravu)$/ })).toBeNull()
  })

  it('decides only PENDING corrections', () => {
    const who = { actor: 'olga', holds: true, ai: false }
    expect(canDecideCorrection({ status: 'APPROVED', proposedBy: 'bert' }, { createdBy: 'dana' }, who)).toBe(false)
    expect(canDecideCorrection({ status: 'PENDING', proposedBy: 'bert' }, { createdBy: 'dana' }, who)).toBe(true)
  })
})
