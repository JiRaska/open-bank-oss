// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { LendingGuaranteeView } from '@/components/lending/LendingGuaranteeView'

const root = '11111111-1111-4111-8111-111111111111'
const related = '22222222-2222-4222-8222-222222222222'
const at = '2026-09-25T12:00:00Z'
const fact = {
  guaranteeId: '33333333-3333-4333-8333-333333333333', contractId: '44444444-4444-4444-8444-444444444444',
  revision: 2, supersedesGuaranteeId: null, guarantorPartyId: '55555555-5555-4555-8555-555555555555',
  capAmount: 120000, currency: 'CZK', coverageFraction: 0.75, seniority: 1, validFrom: at, validTo: null,
  sourceDocumentId: '66666666-6666-4666-8666-666666666666', sourceSha256: 'a'.repeat(64), decidedAt: at,
}
const approved = { loanId: root, effectiveAt: at, knownAt: at, guarantees: [], truncated: false }
const relationships = { rootLoanId: root, effectiveAt: at, knownAt: at, candidateTruncated: true,
  relatedLoansTruncated: true, relatedLoans: [{ loanId: related, guarantees: [fact], truncated: true }] }

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('lending shared guarantor view', () => {
  it('shows source evidence and each partial-view caveat', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/shared-guarantors') ? relationships : approved))))
    render(<LanguageProvider initialLanguage="en"><LendingGuaranteeView loanId={root} /></LanguageProvider>)
    await waitFor(() => expect(screen.getByText(related)).toBeInTheDocument())
    expect(screen.getByText(/candidate loan selection was limited/i)).toBeInTheDocument()
    expect(screen.getByText(/related loan list was limited/i)).toBeInTheDocument()
    expect(screen.getByText(/guarantees for this loan were limited/i)).toBeInTheDocument()
    expect(screen.getByText(fact.guarantorPartyId)).toBeInTheDocument()
    expect(screen.getByText(fact.sourceSha256)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open loan evidence/i })).toHaveAttribute('href', `/lending/loans/${related}/guarantees`)
  })
  it('does not render relationships when the source is disabled', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/shared-guarantors')
      ? new Response(null, { status: 503 }) : new Response(JSON.stringify(approved))))
    render(<LanguageProvider initialLanguage="en"><LendingGuaranteeView loanId={root} /></LanguageProvider>)
    await waitFor(() => expect(screen.getByText(/Verified relationships are unavailable/i)).toBeInTheDocument())
    expect(screen.queryByText(related)).not.toBeInTheDocument()
  })
})
