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

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('AML four-eyes panel', () => {
  it('renders nothing for a case outside transaction monitoring', () => {
    vi.stubGlobal('fetch', vi.fn())
    const { container } = render(<LanguageProvider initialLanguage="en"><AmlFourEyesPanel amlCase={{ ...amlCase, screeningType: 'CUSTOMER_ONBOARDING', requiresFourEyesDisposition: false }} onCaseChanged={() => {}} /></LanguageProvider>)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the pending proposal and approves it with the checker reason, then refreshes the case', async () => {
    const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
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
      return json(String(url).includes('disposition-proposals?') ? [proposal] : [])
    }))
    render(<LanguageProvider initialLanguage="en"><AmlFourEyesPanel amlCase={amlCase} onCaseChanged={() => {}} /></LanguageProvider>)
    fireEvent.click(await screen.findByRole('button', { name: /Approve$/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('someone other than the maker')
  })
})
