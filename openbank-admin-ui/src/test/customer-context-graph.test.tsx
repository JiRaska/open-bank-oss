// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { CustomerContextGraph } from '@/components/party/CustomerContextGraph'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { buildCustomerGraph } from '@/lib/context/customerGraph'
import type { Customer360Evidence } from '@/lib/customer360/evidence'

const PARTY = '11111111-1111-4111-8111-111111111111'
const evidence: Customer360Evidence = {
  available: true, partyId: PARTY, asOf: '2026-09-13 10:00:00.000', excludedCount: 0,
  domains: [{ aggregateType: 'credit_funnel', events: 1, lastEventType: 'credit.funnel.step', lastOccurredAt: '2026-09-13 10:00:00.000' }],
  accountIds: [], consents: [{ consentId: 'consent-alpha', status: 'REVOKED', scopes: ['ACCOUNTS_READ'] }],
}

const account = {
  id: 'account-alpha', accountNumber: 'CZ12…3456', accountType: 'CURRENT', partyId: PARTY,
  productId: 'product-current', currencyCode: 'CZK', status: 'ACTIVE', openedAt: '2026-07-20T10:00:00Z',
}
const card = {
  id: 'card-alpha', partyId: PARTY, accountId: 'account-alpha', productCode: 'VIRTUAL_DEBIT',
  cardType: 'VIRTUAL', network: 'VISA', maskedPan: '411111******1111', status: 'BLOCKED',
  createdAt: '2026-07-24T10:00:00Z', activatedAt: '2026-07-25T10:00:00Z',
  blockedAt: '2026-07-31T10:00:00Z', blockedReason: 'LOST_OR_STOLEN',
}
const notification = {
  id: 'notification-alpha', partyId: PARTY, channel: 'PUSH', template: 'SCA_APPROVAL',
  recipient: 'must-not-render@example.test', subject: 'must not render', status: 'SENT',
  createdAt: '2026-07-24T16:00:00Z', sentAt: '2026-07-24T16:00:01Z', readAt: '2026-07-31T20:00:00Z',
}

function json(body: unknown, ok = true): Response {
  return { ok, status: ok ? 200 : 503, json: async () => body } as Response
}

function installSources(failed = '') {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (!url.includes(`/api/customer-360/${PARTY}/graph`)) throw new Error(`unexpected ${url}`)
    if (failed === 'graph') return json({}, false)
    return json({
      accounts: [account], cards: [card], notifications: failed === 'notifications' ? [] : [notification],
      lendingApplications: [{ id: 'application-alpha', status: 'APPROVED', productKind: 'UNSECURED', createdAt: '2026-07-21T10:00:00Z' }],
      amlCases: [{ id: 'case-alpha', screeningType: 'TRANSACTION_MONITORING', riskLevel: 'MEDIUM', status: 'CLEARED', alertCode: 'TM01', screenedAt: '2026-07-22T10:00:00Z' }],
      devices: [{ id: 'device-alpha', platform: 'IOS', status: 'ACTIVE', registeredAt: '2026-07-23T10:00:00Z' }],
      documents: [{ id: 'document-alpha', templateCode: 'ACCOUNT_AGREEMENT', templateVersion: '1', status: 'GENERATED', productRef: 'product-current', createdAt: '2026-07-20T10:00:00Z' }],
      unavailable: failed ? [failed] : [],
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function graph(data = evidence) {
  return render(<LanguageProvider><CustomerContextGraph evidence={data} partyName="Oldřich Vaněk" /></LanguageProvider>)
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('Customer context graph', () => {
  it('connects live accounts, products, cards and interactions to the customer', async () => {
    const fetchMock = installSources()
    graph()

    expect(await screen.findByRole('button', { name: 'Account: CZ12…3456' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Product: product-current' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Card: 411111******1111' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Interaction: SCA_APPROVAL' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Credit application: UNSECURED' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'AML case: TRANSACTION_MONITORING' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Device: IOS' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Document: ACCOUNT_AGREEMENT' })).toBeInTheDocument()
    expect(screen.getByText('7/7 domain feeds')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('shows card lifecycle evidence without exposing notification destination or content', async () => {
    installSources()
    graph()
    fireEvent.click(await screen.findByRole('button', { name: 'Card: 411111******1111' }))
    const detail = screen.getByRole('complementary', { name: 'Selected node evidence' })
    expect(within(detail).getByText('Status: BLOCKED')).toBeInTheDocument()
    expect(within(detail).getByText('Block reason: LOST_OR_STOLEN')).toBeInTheDocument()
    expect(within(detail).getByText('Source: card-issuance-service')).toBeInTheDocument()
    expect(screen.queryByText('must-not-render@example.test')).not.toBeInTheDocument()
    expect(screen.queryByText('must not render')).not.toBeInTheDocument()
  })

  it('keeps successful domains visible and marks an independently failed source', async () => {
    installSources('notifications')
    graph()
    expect(await screen.findByText(/Unavailable sources: notifications/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Card: 411111******1111' })).toBeInTheDocument()
    expect(screen.getByText('6/7 domain feeds')).toBeInTheDocument()
  })

  it('searches facts as well as labels', async () => {
    installSources()
    graph()
    await screen.findByRole('button', { name: 'Card: 411111******1111' })
    fireEvent.change(screen.getByLabelText('Find in graph'), { target: { value: 'LOST_OR_STOLEN' } })
    expect(screen.getByRole('button', { name: 'Card: 411111******1111' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Account: CZ12…3456' })).not.toBeInTheDocument()
  })

  it('bounds notification nodes and reports truncation', () => {
    const result = buildCustomerGraph(evidence, {
      accounts: [], cards: [], lendingApplications: [], amlCases: [], devices: [], documents: [], unavailable: [],
      notifications: Array.from({ length: 101 }, (_, index) => ({
        id: `notification-${index}`, channel: 'PUSH', template: 'NOTICE', status: 'SENT',
        createdAt: '2026-09-13T10:00:00Z',
      })),
    })
    expect(result.nodes.filter(node => node.kind === 'notification')).toHaveLength(30)
    expect(result.truncated).toBe(true)
  })

  it('does not render any customer surface when the authorized projection is unavailable', async () => {
    installSources()
    graph({ ...evidence, available: false })
    expect(screen.queryByText('Context graph')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('button', { name: /Card:/ })).not.toBeInTheDocument())
  })
})
