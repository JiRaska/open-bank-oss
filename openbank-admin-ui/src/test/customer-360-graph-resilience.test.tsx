// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import Customer360Page from '@/app/customer-360/page'

const PARTY = '11111111-1111-4111-8111-111111111111'

vi.mock('@/components/party/PartySearch', () => ({
  PartySearch: ({ onSelect }: { onSelect: (party: { id: string }) => void }) =>
    <button onClick={() => onSelect({ id: '11111111-1111-4111-8111-111111111111' })}>Select party</button>,
  partyDisplayName: () => 'Oldřich Vaněk',
}))
vi.mock('@/components/party/CustomerContextGraph', () => ({
  CustomerContextGraph: ({ evidence }: { evidence: { available: boolean } }) =>
    <div data-testid="context-graph">{evidence.available ? 'projection and sources' : 'sources only'}</div>,
}))
vi.mock('@/components/party/AdverseStatePanel', () => ({ AdverseStatePanel: () => null }))
vi.mock('@/components/party/LipaPanel', () => ({ LipaPanel: () => null }))
vi.mock('@/components/party/CustomerPortfolioPanel', () => ({ CustomerPortfolioPanel: () => null }))
vi.mock('@/components/party/DevicesPanel', () => ({ DevicesPanel: () => null }))
vi.mock('@/components/party/DocumentsPanel', () => ({ DocumentsPanel: () => null }))

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('Customer 360 graph resilience', () => {
  it('shows independently authorised graph sources while marking analytics unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      expect(url).toBe(`/api/customer-360/${PARTY}`)
      return new Response(JSON.stringify({
        available: false, partyId: PARTY, asOf: null, domains: [], accountIds: [], consents: [],
        excludedCount: 0, error: 'clickhouse unavailable',
      }), { status: 200, headers: { 'content-type': 'application/json' } })
    }))

    render(<LanguageProvider><Customer360Page /></LanguageProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Select party' }))

    expect(await screen.findByTestId('context-graph')).toHaveTextContent('sources only')
    expect(screen.getByText(/ClickHouse \(analytics\)/)).toBeInTheDocument()
    expect(screen.queryByText(/This party has no analytics events/)).not.toBeInTheDocument()
  })
})
