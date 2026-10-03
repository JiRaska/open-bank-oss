// SPDX-License-Identifier: Apache-2.0
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { FraudCaseInvestigation } from '@/components/context/FraudCaseInvestigation'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const id = '11111111-1111-4111-8111-111111111111'
const account = '33333333-3333-4333-8333-333333333333'
const root = { caseId: id, scoreId: '22222222-2222-4222-8222-222222222222', accountId: account, counterpartyId: null, status: 'OPEN', revision: 1, openedAt: '2026-09-17T12:00:00Z', closedAt: null }
const related = { ...root, caseId: '44444444-4444-4444-8444-444444444444', scoreId: '55555555-5555-4555-8555-555555555555' }

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('Fraud case investigation', () => {
  it('shows a source-backed, visibly partial graph and selectable evidence', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ root, related: [{ evidence: related, shared: [{ type: 'ACCOUNT', sourceId: account }] }], inspectedCandidates: 1, comparedCandidates: 6, candidateTruncated: true })))
    vi.stubGlobal('fetch', fetcher)
    render(<LanguageProvider initialLanguage="en"><FraudCaseInvestigation initialCaseId={id} /></LanguageProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Open graph' }))

    expect(await screen.findByRole('img', { name: 'Explicit Fraud evidence graph' })).toHaveTextContent('RELATED CASE')
    expect(screen.getByText(/additional assigned cases were not searched/)).toBeInTheDocument()
    expect(screen.getByText('6 assigned cases compared')).toBeInTheDocument()
    expect(screen.getByText(/not a fraud finding/)).toBeInTheDocument()
    expect(fetcher).toHaveBeenCalledWith(`/api/context/fraud-cases/${id}/network`, { cache: 'no-store' })
    fireEvent.click(screen.getByRole('button', { name: /Evidence match/ }))
    expect(screen.getByText(related.scoreId)).toBeInTheDocument()
  })

  it('clears previous evidence when access is denied', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ root, related: [], inspectedCandidates: 0, candidateTruncated: false })))
      .mockResolvedValueOnce(new Response(null, { status: 403 }))
    vi.stubGlobal('fetch', fetcher)
    render(<LanguageProvider initialLanguage="en"><FraudCaseInvestigation initialCaseId={id} /></LanguageProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Open graph' }))
    expect(await screen.findByRole('img', { name: 'Explicit Fraud evidence graph' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Open graph' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Access was denied')
    expect(screen.queryByRole('img', { name: 'Explicit Fraud evidence graph' })).not.toBeInTheDocument()
  })
})
