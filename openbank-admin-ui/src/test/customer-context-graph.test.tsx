// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { CustomerContextGraph } from '@/components/party/CustomerContextGraph'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { buildCustomerGraph, selectGraphFocus, type CustomerGraph } from '@/lib/context/customerGraph'
import type { Customer360Evidence } from '@/lib/customer360/evidence'

const PARTY = '11111111-1111-4111-8111-111111111111'
const ACCOUNT_ID = '22222222-2222-4222-8222-222222222222'
const CARD_ID = '33333333-3333-4333-8333-333333333333'
const APPLICATION_ID = '44444444-4444-4444-8444-444444444444'
const evidence: Customer360Evidence = {
  available: true, partyId: PARTY, asOf: '2026-09-13 10:00:00.000', excludedCount: 0,
  domains: [{ aggregateType: 'credit_funnel', events: 1, lastEventType: 'credit.funnel.step', lastOccurredAt: '2026-09-13 10:00:00.000' }],
  accountIds: [], consents: [{ consentId: 'consent-alpha', status: 'REVOKED', scopes: ['ACCOUNTS_READ'] }],
}

const account = {
  id: ACCOUNT_ID, accountNumber: 'CZ12…3456', accountType: 'CURRENT', partyId: PARTY,
  productId: 'product-current', currencyCode: 'CZK', status: 'ACTIVE', openedAt: '2026-07-20T10:00:00Z',
}
const card = {
  id: CARD_ID, partyId: PARTY, accountId: ACCOUNT_ID, productCode: 'VIRTUAL_DEBIT',
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

function installSources(failed = '', accountCount = 1) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (!url.includes(`/api/customer-360/${PARTY}/graph`)) throw new Error(`unexpected ${url}`)
    if (failed === 'graph') return json({}, false)
    return json({
      accounts: accountCount === 1 ? [account] : Array.from({ length: accountCount }, (_, index) => ({
        ...account, id: `account-${index}`, accountNumber: `CZ-${index}`,
      })),
      cards: [card], notifications: failed === 'notifications' ? [] : [notification],
      lendingApplications: [{ id: APPLICATION_ID, status: 'APPROVED', productKind: 'UNSECURED', createdAt: '2026-07-21T10:00:00Z' }],
      amlCases: [{ id: 'case-alpha', screeningType: 'TRANSACTION_MONITORING', riskLevel: 'MEDIUM', status: 'CLEARED', alertCode: 'TM01', screenedAt: '2026-07-22T10:00:00Z' }],
      devices: [{ id: 'device-alpha', platform: 'IOS', status: 'ACTIVE', registeredAt: '2026-07-23T10:00:00Z' }],
      documents: [{ id: 'document-alpha', templateCode: 'ACCOUNT_AGREEMENT', templateVersion: '1', status: 'GENERATED', productRef: 'product-current', createdAt: '2026-07-20T10:00:00Z' }],
      unavailable: failed ? [failed] : [], truncated: [],
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
  it('keeps later search matches that fit after a longer source chain exceeds the visible budget', () => {
    const nodes: CustomerGraph['nodes'] = [
      { id: 'account:one', kind: 'account', label: 'Account', source: 'account', facts: [] },
      { id: 'card:one', kind: 'card', label: 'Card', source: 'card', facts: [] },
      { id: 'notification:one', kind: 'notification', label: 'Interaction', source: 'notification', facts: [] },
    ]
    const sourceGraph: CustomerGraph = {
      nodes,
      edges: [
        { id: 'owns-account', from: 'customer', to: 'account:one', relation: 'OWNS' },
        { id: 'account-card', from: 'account:one', to: 'card:one', relation: 'HAS_CARD' },
        { id: 'customer-notification', from: 'customer', to: 'notification:one', relation: 'RECEIVED' },
      ],
      truncated: false,
    }

    expect(selectGraphFocus(sourceGraph, [nodes[1], nodes[2]], 1).map(node => node.id))
      .toEqual(['notification:one'])
  })

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
    expect(document.querySelectorAll('animateMotion').length).toBeLessThanOrEqual(16)
    fireEvent.click(screen.getByRole('button', { name: 'Pause flow' }))
    expect(document.querySelectorAll('animateMotion')).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Resume flow' })).toHaveAttribute('aria-pressed', 'false')
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
    expect(within(detail).getByRole('link', { name: 'Open source record' })).toHaveAttribute('href', `/cards/${CARD_ID}`)
    expect(screen.queryByText('must-not-render@example.test')).not.toBeInTheDocument()
    expect(screen.queryByText('must not render')).not.toBeInTheDocument()
  })

  it('links only live source records with valid internal identifiers', () => {
    const result = buildCustomerGraph(evidence, {
      accounts: [account], cards: [card], notifications: [],
      lendingApplications: [{ id: APPLICATION_ID, status: 'APPROVED', productKind: 'UNSECURED', createdAt: '' }],
      amlCases: [], devices: [], documents: [], unavailable: [], truncated: [],
    })
    expect(result.nodes.find(node => node.id === `account:${ACCOUNT_ID}`)?.href).toBe(`/accounts/${ACCOUNT_ID}`)
    expect(result.nodes.find(node => node.id === `card:${CARD_ID}`)?.href).toBe(`/cards/${CARD_ID}`)
    expect(result.nodes.find(node => node.id === `application:${APPLICATION_ID}`)?.href).toBe(`/lending/applications/${APPLICATION_ID}`)
    expect(result.nodes.find(node => node.id === 'domain:credit_funnel')?.href).toBeUndefined()

    const invalid = buildCustomerGraph(evidence, {
      accounts: [{ ...account, id: '../../other-route' }], cards: [], notifications: [],
      lendingApplications: [], amlCases: [], devices: [], documents: [], unavailable: [], truncated: [],
    })
    expect(invalid.nodes.find(node => node.id === 'account:../../other-route')?.href).toBeUndefined()
  })

  it('keeps successful domains visible and marks an independently failed source', async () => {
    installSources('notifications')
    graph()
    expect(await screen.findByText(/Unavailable sources: notifications/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Card: 411111******1111' })).toBeInTheDocument()
    expect(screen.getByText('6/7 domain feeds')).toBeInTheDocument()
  })

  it('keeps interactions and other domains in the bounded overview when accounts are numerous', async () => {
    installSources('', 50)
    graph()
    expect(await screen.findByRole('button', { name: 'Interaction: SCA_APPROVAL' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Card: 411111******1111' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Credit application: UNSECURED' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'AML case: TRANSACTION_MONITORING' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Document: ACCOUNT_AGREEMENT' })).toBeInTheDocument()
    expect(screen.getByText(/Visible nodes: 48/)).toBeInTheDocument()
  })

  it('searches facts as well as labels', async () => {
    installSources()
    graph()
    await screen.findByRole('button', { name: 'Card: 411111******1111' })
    fireEvent.change(screen.getByLabelText('Find in graph'), { target: { value: 'LOST_OR_STOLEN' } })
    expect(screen.getByRole('button', { name: 'Card: 411111******1111' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Account: CZ12…3456' })).toBeInTheDocument()
    expect(Array.from(document.querySelectorAll('svg title')).some(title => title.textContent === 'HAS_CARD')).toBe(true)
    expect(screen.getByText('1 matches · 2 nodes including context')).toBeInTheDocument()
  })

  it('bounds notification nodes and reports truncation', () => {
    const result = buildCustomerGraph(evidence, {
      accounts: [], cards: [], lendingApplications: [], amlCases: [], devices: [], documents: [], unavailable: [], truncated: [],
      notifications: Array.from({ length: 101 }, (_, index) => ({
        id: `notification-${index}`, channel: 'PUSH', template: 'NOTICE', status: 'SENT',
        createdAt: '2026-09-13T10:00:00Z',
      })),
    })
    expect(result.nodes.filter(node => node.kind === 'notification')).toHaveLength(30)
    expect(result.truncated).toBe(true)
  })

  it('labels projected consent freshness without claiming an effective consent time', () => {
    const result = buildCustomerGraph({
      ...evidence,
      consents: [
        { consentId: 'consent-alpha', status: 'ACTIVE', scopes: ['ACCOUNTS_READ'] },
        { consentId: 'consent-alpha', status: 'REVOKED', scopes: ['ACCOUNTS_READ'] },
      ],
    }, {
      accounts: [], cards: [], notifications: [], lendingApplications: [], amlCases: [],
      devices: [], documents: [], unavailable: [], truncated: [],
    })
    const consent = result.nodes.filter(node => node.kind === 'consent')
    expect(consent).toHaveLength(1)
    expect(consent[0].facts).toContain('Projected state: REVOKED')
    expect(consent[0].facts).toContain('Projection newest event: 2026-09-13 10:00:00.000 (not the consent event time)')
  })

  it('keeps account links resolvable when referenced accounts exceed the display cap', () => {
    const result = buildCustomerGraph(evidence, {
      accounts: Array.from({ length: 51 }, (_, index) => ({ ...account, id: `account-${index}` })),
      cards: [{ ...card, accountId: 'account-50' }],
      amlCases: [{
        id: 'aml-other-account', accountId: 'account-from-aml', transactionId: '',
        screeningType: 'TRANSACTION_MONITORING', riskLevel: 'MEDIUM', status: 'OPEN',
        alertCode: 'TM01', screenedAt: '2026-09-13T10:00:00Z',
      }],
      notifications: [], lendingApplications: [], devices: [], documents: [], unavailable: [], truncated: [],
    })
    const nodeIds = new Set(result.nodes.map(node => node.id))
    expect(result.edges.every(edge => edge.from === 'customer' || nodeIds.has(edge.from))).toBe(true)
    expect(result.edges.every(edge => nodeIds.has(edge.to))).toBe(true)
    expect(result.nodes.filter(node => node.id === 'account:account-50')).toHaveLength(1)
    expect(result.nodes.filter(node => node.id === 'account:account-from-aml')).toHaveLength(1)
    expect(result.truncated).toBe(true)
  })

  it('does not render any customer surface when the authorized projection is unavailable', async () => {
    installSources()
    graph({ ...evidence, available: false })
    expect(screen.queryByText('Context graph')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('button', { name: /Card:/ })).not.toBeInTheDocument())
  })
})
