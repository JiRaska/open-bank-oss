// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { CustomerContextGraph } from '@/components/party/CustomerContextGraph'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import type { Customer360Evidence } from '@/lib/customer360/evidence'

const evidence: Customer360Evidence = {
  available: true, partyId: '11111111-1111-4111-8111-111111111111',
  asOf: '2026-09-13 10:00:00.000', excludedCount: 0,
  domains: [{ aggregateType: 'transaction', events: 4, lastEventType: 'TransactionBooked', lastOccurredAt: '2026-09-13 10:00:00.000' }],
  accountIds: ['account-alpha'], consents: [{ consentId: 'consent-alpha', status: 'REVOKED', scopes: ['ACCOUNTS_READ'] }],
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

function graph(data = evidence) {
  return render(<LanguageProvider><CustomerContextGraph key={data.partyId} evidence={data} partyName="Synthetic customer" /></LanguageProvider>)
}

describe('Customer context graph', () => {
  it('groups repeated consent observations without inventing a current state or combining scopes', () => {
    graph({ ...evidence, consents: [
      { consentId: 'same-consent', status: 'ACTIVE', scopes: ['ACCOUNTS_READ'] },
      { consentId: 'same-consent', status: 'REVOKED', scopes: ['PAYMENTS'] },
    ] })
    expect(screen.getAllByRole('button', { name: 'Consent: same-consent' })).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Consent: same-consent' }))
    expect(screen.getByText('Observation 1: ACTIVE')).toBeInTheDocument()
    expect(screen.getByText('Observation 2: REVOKED')).toBeInTheDocument()
    expect(screen.queryByText(/Projected state:/)).not.toBeInTheDocument()
    expect(screen.queryByText('Scopes: ACCOUNTS_READ, PAYMENTS')).not.toBeInTheDocument()
  })

  it('bounds observation detail independently of canvas size', () => {
    graph({ ...evidence, consents: Array.from({ length: 5000 }, (_, index) => ({
      consentId: 'same-consent', status: `STATE_${index}`, scopes: [],
    })) })
    fireEvent.click(screen.getByRole('button', { name: 'Consent: same-consent' }))
    expect(screen.getAllByText(/^Observation \d+:/)).toHaveLength(10)
    expect(screen.getByText(/Showing 10 of 5000 observations/)).toBeInTheDocument()
    expect(screen.queryByText('Observation 11: STATE_10')).not.toBeInTheDocument()
  })

  it('uses supplied evidence without fetching and supports keyboard evidence inspection', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    graph()
    fireEvent.keyDown(screen.getByRole('button', { name: 'Consent: consent-alpha' }), { key: 'Enter' })
    const detail = screen.getByRole('complementary', { name: 'Selected node evidence' })
    expect(within(detail).getByText('Projected state: REVOKED')).toBeInTheDocument()
    expect(within(detail).getByText('Scopes: ACCOUNTS_READ')).toBeInTheDocument()
    expect(within(detail).getByText(/does not establish current ownership/)).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('filters and clears details that are no longer in the visible graph', () => {
    graph()
    fireEvent.click(screen.getByRole('button', { name: 'Account: account-alpha' }))
    fireEvent.change(screen.getByLabelText('Node type'), { target: { value: 'domain' } })
    expect(screen.queryByRole('button', { name: 'Account: account-alpha' })).not.toBeInTheDocument()
    expect(screen.getByText(/Select a node in the graph/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Find in graph'), { target: { value: 'absent' } })
    expect(screen.getByText('No nodes match this filter.')).toBeInTheDocument()
  })

  it('bounds canvas work to twelve nodes and makes the remainder reachable', () => {
    graph({ ...evidence, domains: [], consents: [], accountIds: Array.from({ length: 25 }, (_, index) => `account-${index}`) })
    const map = screen.getByRole('group', { name: 'Relationships in the customer projection' })
    expect(within(map).getAllByRole('button')).toHaveLength(12)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(within(map).getAllByRole('button')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Account: account-24' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })

  it('does not render unavailable evidence or invent connections for an empty projection', () => {
    const { unmount } = graph({ ...evidence, available: false })
    expect(screen.queryByText('Context graph')).not.toBeInTheDocument()
    unmount()
    graph({ ...evidence, domains: [], accountIds: [], consents: [] })
    expect(screen.getByText('No projected relationships are available for this customer.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Account:/ })).not.toBeInTheDocument()
  })

  it('resets the selected evidence when the parent switches the party key', () => {
    const { rerender } = graph()
    fireEvent.click(screen.getByRole('button', { name: 'Consent: consent-alpha' }))
    const next = { ...evidence, partyId: '22222222-2222-4222-8222-222222222222', domains: [], accountIds: [], consents: [] }
    rerender(<LanguageProvider><CustomerContextGraph key={next.partyId} evidence={next} partyName="Second synthetic customer" /></LanguageProvider>)
    expect(screen.queryByText('Projected state: REVOKED')).not.toBeInTheDocument()
    expect(screen.queryByText('consent-alpha')).not.toBeInTheDocument()
  })
})
