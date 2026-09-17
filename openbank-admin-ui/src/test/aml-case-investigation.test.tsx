// SPDX-License-Identifier: Apache-2.0
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AmlCaseInvestigation } from '@/components/context/AmlCaseInvestigation'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const caseId = '66666666-6666-4666-8666-666666666666'
const eventId = '77777777-7777-4777-8777-777777777777'
const history = {
  root: `aml-case:${caseId}`, effectiveAt: '2026-03-01T00:00:00Z', knownAt: '2026-03-01T00:00:00Z', truncated: false,
  observations: [{
    evidence: {
      eventId, caseId, partyId: '88888888-8888-4888-8888-888888888888',
      accountId: '99999999-9999-4999-8999-999999999999', transactionId: null,
      eventType: 'aml.case.created.v1', status: 'OPEN', previousStatus: null,
      riskLevel: 'LOW', screeningType: 'MANUAL_INVESTIGATION', occurredAt: '2026-02-01T00:00:00Z',
    },
    recordedAt: '2026-02-02T00:00:00Z', evidenceRef: `aml-case:${caseId}:${eventId}`, contentHash: 'a'.repeat(64),
  }],
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('AML case investigation', () => {
  it('shows only validated source-linked nodes and evidence provenance', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(history))))
    render(<LanguageProvider initialLanguage="en"><AmlCaseInvestigation initialCaseId={caseId} /></LanguageProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Explore connections' }))
    expect(await screen.findByRole('img', { name: 'Observed AML relationship graph' })).toHaveTextContent('Account')
    expect(screen.getByText(/SHA-256:/)).toHaveTextContent('a'.repeat(64))
    expect(screen.getByText(/not proven fraud/)).toBeInTheDocument()
  })

  it('fails closed on malformed evidence and clears it when the case changes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...history, root: 'aml-case:11111111-1111-4111-8111-111111111111' }))))
    render(<LanguageProvider initialLanguage="en"><AmlCaseInvestigation initialCaseId={caseId} /></LanguageProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Explore connections' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be verified safely')
    expect(screen.queryByRole('img', { name: 'Observed AML relationship graph' })).not.toBeInTheDocument()
  })

  it('does not retain evidence after access is denied', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 403 })))
    render(<LanguageProvider initialLanguage="en"><AmlCaseInvestigation initialCaseId={caseId} /></LanguageProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Explore connections' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('not permitted')
    expect(screen.queryByRole('img', { name: 'Observed AML relationship graph' })).not.toBeInTheDocument()
  })
})
