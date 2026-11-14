// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AmlFourEyesPanel } from '@/components/aml/AmlFourEyesPanel'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import type { AmlCase } from '@/lib/aml/amlCaseContract'

const amlCase: AmlCase = {
  id: '11111111-1111-4111-8111-111111111111', partyId: '22222222-2222-4222-8222-222222222222',
  accountId: null, transactionId: null, customerReference: 'tm:1', screeningType: 'TRANSACTION_MONITORING',
  riskLevel: 'HIGH', status: 'UNDER_REVIEW', alertCode: 'TM_VELOCITY', alertDetail: null, matchedEntity: null,
  decisionReason: null, assignedAnalyst: 'alice', decidedBy: null, screenedAt: '2026-11-01T00:00:00Z',
  decidedAt: null, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', requiresFourEyesDisposition: true,
}
const proposal = {
  proposalId: 'p-1', caseId: amlCase.id, disposition: 'CLEAR', outcome: { code: 'FALSE_POSITIVE_EXPLAINED_ACTIVITY' },
  note: 'Salary from a known employer', state: 'PROPOSED', proposedBy: 'alice', proposedAt: '2026-11-12T10:00:00Z',
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const vocabulary = { vocabularyId: 'v', vocabularyRevision: 1, vocabularyHash: 'h', entries: [
  { code: 'FALSE_POSITIVE_EXPLAINED_ACTIVITY', label: 'False positive: activity explained', meaning: 'm' },
  { code: 'CONFIRMED_SUSPICION', label: 'Suspicion confirmed', meaning: 'm' },
] }

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('AML four-eyes panel', () => {
  it('renders nothing for a case outside transaction monitoring', () => {
    vi.stubGlobal('fetch', vi.fn())
    const { container } = render(<LanguageProvider initialLanguage="en"><AmlFourEyesPanel amlCase={{ ...amlCase, screeningType: 'CUSTOMER_ONBOARDING', requiresFourEyesDisposition: false }} onCaseChanged={() => {}} /></LanguageProvider>)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the pending proposal and approves it with the checker reason, then refreshes the case', async () => {
    const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith('/outcomes')) return json(vocabulary)
      if (String(url).includes('disposition-proposals?')) return json([proposal, { ...proposal, caseId: 'other' }])
      if (String(url).includes('/reports?')) return json([])
      if (init?.method === 'POST') return json({ ...proposal, state: 'EXECUTED', decidedBy: 'bob' })
      return json([], 404)
    })
    vi.stubGlobal('fetch', fetcher)
    const changed = vi.fn()
    render(<LanguageProvider initialLanguage="en"><AmlFourEyesPanel amlCase={amlCase} onCaseChanged={changed} /></LanguageProvider>)
    expect(await screen.findByText('Awaiting second approval')).toBeInTheDocument()
    expect(screen.getByText('Salary from a known employer')).toBeInTheDocument()
    expect(await screen.findByText(/activity explained/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText("Approver's reason"), { target: { value: 'Payslips checked' } })
    fireEvent.click(screen.getByRole('button', { name: /Approve$/ }))
    await waitFor(() => expect(changed).toHaveBeenCalled())
    const post = fetcher.mock.calls.find(c => c[1]?.method === 'POST')!
    expect(String(post[0])).toContain('/disposition-proposals/p-1/decision')
    expect(JSON.parse(String(post[1]!.body))).toEqual({ approve: true, reason: 'Payslips checked' })
  })

  it('surfaces the maker-checker refusal instead of a generic error', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return json({ message: 'the approver is the maker' }, 422)
      if (String(url).endsWith('/outcomes')) return json(vocabulary)
      return json(String(url).includes('disposition-proposals?') ? [proposal] : [])
    }))
    render(<LanguageProvider initialLanguage="en"><AmlFourEyesPanel amlCase={amlCase} onCaseChanged={() => {}} /></LanguageProvider>)
    fireEvent.click(await screen.findByRole('button', { name: /Approve$/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('someone other than the maker')
  })

  it('offers no outcome to choose, and cannot send a proposal, when the vocabulary is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (String(url).endsWith('/outcomes')) return json({ error: 'upstream_error' }, 502)
      return json([])
    }))
    render(<LanguageProvider initialLanguage="en"><AmlFourEyesPanel amlCase={amlCase} onCaseChanged={() => {}} /></LanguageProvider>)
    const send = await screen.findByRole('button', { name: 'Send for approval' })
    expect(send).toBeDisabled()
    expect(screen.getByLabelText('Assessment outcome *').querySelectorAll('option')).toHaveLength(1)
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})
