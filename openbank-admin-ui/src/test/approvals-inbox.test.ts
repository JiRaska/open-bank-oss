// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Integration tests for the federated approval inbox read (/api/approvals/pending, ADR-0227 D2).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({
  auth: vi.fn(),
}))

import { auth } from '@/auth'
import { parseApprovalInbox } from '@/lib/approvals/evidence'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } }

async function route(): Promise<typeof import('@/app/api/approvals/pending/route')> {
  return import('@/app/api/approvals/pending/route')
}

describe('federated approvals inbox (ADR-0227 D2)', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('401s without a session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const res = await (await route()).GET()
    expect(res.status).toBe(401)
  })

  it('merges every configured domain queue into canonical items, sorted by proposedAt', async () => {
    const mock = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/api/v1/sca/approvals') || url.includes('/api/v1/settlements/approvals')) {
        return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      }
      if (url.includes('/api/v1/lending/ledger-backfill/requests')) {
        return Promise.resolve(new Response(JSON.stringify({ requests: [] }), { status: 200 }))
      }
      if (url.includes('/api/v1/lending/compliance-packs/proposals/pending')) {
        return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      }
      if (url.includes('lending')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'L-2', action: 'lending.disburse', resourceId: 'loan-2', makerId: 'officer.b', createdAt: '2026-07-30T10:00:00Z' },
          { id: 'L-1', action: 'lending.writeoff', resourceId: 'loan-1', makerId: 'officer.a', createdAt: '2026-07-29T09:00:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('sanctions')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'S-1', action: 'sanctions.clear', resourceId: 'hit-9', makerId: 'analyst.c', createdAt: '2026-07-29T12:00:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('clearing')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'C-1', action: 'clearingBatch.settle', resourceId: 'batch-7', makerId: 'operator.d', createdAt: '2026-07-29T11:00:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('journals/approvals') || url.includes('ledger')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'J-1', action: 'ledger.reverse', resourceId: 'journal-7', makerId: 'operator.d', createdAt: '2026-07-29T11:00:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('swift/approvals') || url.includes('swift-service')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'W-1', action: 'swift.send', resourceId: null, makerId: 'operator.d', createdAt: '2026-07-29T11:00:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('transaction')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'T-1', action: 'transaction.reverse', resourceId: 'txn-9', makerId: 'teller.d', createdAt: '2026-07-30T09:30:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('domestic-payments/approvals') || url.includes('domestic-payment')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'D-1', action: 'domestic-payment.transitionStatus', resourceId: 'payment-7', makerId: 'operator.d', createdAt: '2026-07-29T11:00:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('sepa-payments/approvals') || url.includes('sepa-payment')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'SP-1', action: 'sepaPayment.transitionStatus', resourceId: 'payment-7', makerId: 'operator.d', createdAt: '2026-07-29T11:00:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('fx/approvals')) {
        // 11:30, deliberately NOT the 11:00 domestic-payment/clearing/ledger/swift/sepaPayment
        // carries: a tie would make the expected order depend on the concat order in route.ts
        // rather than on proposedAt.
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'F-1', action: 'fx.convert', resourceId: 'conv-3', makerId: 'trader.d', createdAt: '2026-07-29T11:30:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('sepa-instant')) {
        // Also 11:30, same as fx — ties F-1 and I-1 to each other, testing that the stable-sort
        // concat order (fx before sepa-instant in route.ts) breaks the tie deterministically.
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'I-1', action: 'sctInstPayment.recall', resourceId: 'payment-9', makerId: 'operator.e', createdAt: '2026-07-29T11:30:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('notification')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'N-1', action: 'opsmessage.compose', resourceId: null, makerId: 'operator.f', createdAt: '2026-07-29T10:30:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('parties/approvals') || url.includes('party-service')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'PT-1', action: 'party.merge', resourceId: 'party-7', makerId: 'operator.g', createdAt: '2026-07-29T11:45:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('accounts/approvals') || url.includes('account-service')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'A-1', action: 'account.freeze', resourceId: 'account-7', makerId: 'operator.h', createdAt: '2026-07-29T11:50:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('consents/approvals') || url.includes('consent-service')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'CO-1', action: 'consent.revoke', resourceId: 'consent-7', makerId: 'operator.h', createdAt: '2026-07-29T11:50:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('balances/approvals') || url.includes('balance-service')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'B-1', action: 'balance.debit', resourceId: 'account-7', makerId: 'operator.i', createdAt: '2026-07-29T11:55:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('fees/approvals') || url.includes('billing-service')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'BL-1', action: 'billing.post', resourceId: 'fee-7', makerId: 'operator.j', createdAt: '2026-07-29T11:57:00Z' },
        ]), { status: 200 }))
      }
      // Empty, not omitted: without an explicit branch this URL falls through to the agent
      // catch-all below and doubles up P-1 under a different domain label — exactly the
      // "unread source is indistinguishable from an empty one" trap this file's other tests
      // are named for, just self-inflicted by an unguarded fallback instead of a missing fetch.
      if (url.includes('communications/approvals') || url.includes('communication-service')) {
        return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      }
      if (url.includes('delegations/approvals') || url.includes('delegation-service')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'DG-1', delegationId: 'grant-7', operation: 'REINSTATE', proposedBy: 'operator.k', proposedAt: '2026-07-29T11:58:00Z' },
        ]), { status: 200 }))
      }
      if (url.includes('/api/v1/treasury/deals')) {
        return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      }
      if (url.includes('/api/v1/campaigns') || url.includes('/api/v1/audiences') || url.includes('/api/v1/parties/cases')) {
        return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      }
      return Promise.resolve(new Response(JSON.stringify([
        { id: 'P-1', suggestedAction: 'agent.research', proposedBy: 'ui-assistant', proposedAt: '2026-07-30T08:00:00Z' },
      ]), { status: 200 }))
    })
    vi.stubGlobal('fetch', mock)

    const res = await (await route()).GET()
    const body = await res.json()
    // D-1, C-1, J-1, W-1 and SP-1 all sit at 11:00 (domestic-payment, clearing, ledger, swift,
    // sepaPayment); F-1 and I-1 both sit at 11:30 (fx before sepa-instant) — both ties resolved
    // by the stable-sort's concat order in route.ts. N-1 sits at 10:30, between L-1 and the
    // 11:00 tie.
    expect(body.items.map((i: { id: string }) => i.id)).toEqual(['L-1', 'N-1', 'D-1', 'C-1', 'J-1', 'W-1', 'SP-1', 'F-1', 'I-1', 'PT-1', 'A-1', 'CO-1', 'B-1', 'BL-1', 'DG-1', 'S-1', 'P-1', 'T-1', 'L-2'])
    expect(body.items[0]).toMatchObject({ domain: 'lending', action: 'lending.writeoff', maker: 'officer.a' })
    expect(body.items[1]).toMatchObject({ domain: 'notification', action: 'opsmessage.compose', maker: 'operator.f' })
    expect(body.items[2]).toMatchObject({ domain: 'domestic-payment', action: 'domestic-payment.transitionStatus', maker: 'operator.d' })
    expect(body.items[3]).toMatchObject({ domain: 'clearing', action: 'clearingBatch.settle', maker: 'operator.d' })
    expect(body.items[4]).toMatchObject({ domain: 'ledger', action: 'ledger.reverse', maker: 'operator.d' })
    expect(body.items[5]).toMatchObject({ domain: 'swift', action: 'swift.send', maker: 'operator.d' })
    expect(body.items[6]).toMatchObject({ domain: 'sepa-payment', action: 'sepaPayment.transitionStatus', maker: 'operator.d' })
    expect(body.items[7]).toMatchObject({ domain: 'fx', action: 'fx.convert', maker: 'trader.d' })
    expect(body.items[8]).toMatchObject({ domain: 'sepa-instant', action: 'sctInstPayment.recall', maker: 'operator.e' })
    expect(body.items[9]).toMatchObject({ domain: 'party', action: 'party.merge', maker: 'operator.g' })
    expect(body.items[10]).toMatchObject({ domain: 'account', action: 'account.freeze', maker: 'operator.h' })
    expect(body.items[11]).toMatchObject({ domain: 'consent', action: 'consent.revoke', maker: 'operator.h' })
    expect(body.items[12]).toMatchObject({ domain: 'balance', action: 'balance.debit', maker: 'operator.i' })
    expect(body.items[13]).toMatchObject({ domain: 'billing', action: 'billing.post', maker: 'operator.j' })
    expect(body.items[14]).toMatchObject({ domain: 'delegation', action: 'delegation.reinstate', maker: 'operator.k', resourceId: 'grant-7' })
    expect(body.items[15]).toMatchObject({ domain: 'sanctions', action: 'sanctions.clear', maker: 'analyst.c' })
    expect(body.items[16]).toMatchObject({ domain: 'agent', action: 'agent.research' })
    expect(body.items[17]).toMatchObject({ domain: 'transaction', action: 'transaction.reverse', maker: 'teller.d' })
    expect(body.sources.party).toBe('ok')
    expect(body.sources.account).toBe('ok')
    expect(body.sources.balance).toBe('ok')
    expect(body.sources.billing).toBe('ok')
    expect(body.sources.delegation).toBe('ok')
    expect(body.sources.communication).toBe('ok')
    expect(body.sources.treasury).toBe('ok')
    expect(body.sources['ledger-backfill']).toBe('ok')
    expect(body.sources['compliance-pack']).toBe('ok')
    expect(body.sources.campaign).toBe('ok')
    expect(body.sources.audience).toBe('ok')
    expect(body.sources['identity-case']).toBe('ok')
    expect(body.sources.sca).toBe('ok')
    expect(body.sources.settlement).toBe('ok')
    // The UI's real consumer must accept the route's own output, not only a hand-built fixture.
    expect(parseApprovalInbox(body)).toEqual(body)
  })

  it('reads distinct four-eyes queues without inventing timestamps or exposing identity-case PII', async () => {
    const seen: Array<{ url: string; authorization: string | null }> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      seen.push({ url: String(url), authorization: new Headers(init?.headers).get('authorization') })
      if (String(url).includes('/compliance-packs/proposals/pending')) return new Response(JSON.stringify([
        { id: 'pack-7', state: 'PROPOSED', proposedBy: 'risk.officer', proposedAt: '2026-09-20T08:00:00Z' },
      ]))
      if (String(url).includes('/api/v1/campaigns')) return new Response(JSON.stringify([
        { id: 'campaign-7', state: 'PENDING_APPROVAL', createdBy: 'marketer.one', updatedAt: '2026-09-20T09:00:00Z' },
        { id: 'campaign-6', state: 'ACTIVE', createdBy: 'marketer.two', updatedAt: '2026-09-19T09:00:00Z' },
      ]))
      if (String(url).includes('/api/v1/audiences')) return new Response(JSON.stringify([
        { name: 'newcomers', version: 3, state: 'PENDING_APPROVAL', createdBy: 'marketer.three' },
      ]))
      if (String(url).includes('/api/v1/parties/cases')) return new Response(JSON.stringify([
        { id: 'case-7', status: 'AWAITING_SECOND_APPROVAL', firstApprover: 'checker.one', firstAt: '2026-09-20T10:00:00Z', applicant: { givenName: 'Sensitive' } },
        { id: 'case-6', status: 'OPEN', firstApprover: null, firstAt: null, applicant: { givenName: 'Private' } },
      ]))
      if (String(url).includes('/ledger-backfill/requests')) return new Response(JSON.stringify({ requests: [] }))
      return new Response(JSON.stringify([]))
    }))

    const body = await (await (await route()).GET()).json()
    for (const path of ['/compliance-packs/proposals/pending', '/api/v1/campaigns', '/api/v1/audiences', '/api/v1/parties/cases']) {
      expect(seen.find(call => call.url.includes(path))?.authorization).toBe('Bearer operator-token')
    }
    expect(body.items).toEqual([
      { id: 'newcomers@3', domain: 'audience', action: 'campaign.audience.approve', resourceId: 'newcomers@3', maker: 'marketer.three', makerActorKind: 'UNKNOWN', proposedAt: null },
      { id: 'pack-7', domain: 'compliance-pack', action: 'lending.compliancePack.activate', resourceId: 'pack-7', maker: 'risk.officer', makerActorKind: 'UNKNOWN', proposedAt: '2026-09-20T08:00:00Z' },
      { id: 'campaign-7', domain: 'campaign', action: 'campaign.activate', resourceId: 'campaign-7', maker: 'marketer.one', makerActorKind: 'UNKNOWN', proposedAt: '2026-09-20T09:00:00Z' },
      { id: 'case-7', domain: 'identity-case', action: 'identity.case.secondApproval', resourceId: 'case-7', maker: 'checker.one', makerActorKind: 'UNKNOWN', proposedAt: '2026-09-20T10:00:00Z' },
    ])
    expect(JSON.stringify(body)).not.toContain('Sensitive')
    expect(JSON.stringify(body)).not.toContain('Private')
  })

  it('preserves maker IDs and submission times without guessing actor kind for treasury and ledger backfill', async () => {
    const seen: Array<{ url: string; authorization: string | null }> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      seen.push({ url: String(url), authorization: new Headers(init?.headers).get('authorization') })
      if (String(url).includes('/api/v1/treasury/deals')) return new Response(JSON.stringify([{
        dealId: 'deal-7', state: 'PENDING_APPROVAL', product: 'MM_PLACEMENT', createdBy: 'dealer.one',
        submittedBy: 'dealer.two', history: [{ to: 'DRAFT', at: '2026-09-20T09:00:00Z' }, { to: 'PENDING_APPROVAL', at: '2026-09-20T10:00:00Z' }],
      }]))
      if (String(url).includes('/api/v1/lending/ledger-backfill/requests')) return new Response(JSON.stringify({ requests: [
        { id: 'request-7', state: 'PROPOSED', proposedBy: 'finance.one', proposedAt: '2026-09-20T11:00:00Z' },
        { id: 'request-6', state: 'APPROVED', proposedBy: 'finance.two', proposedAt: '2026-09-19T11:00:00Z' },
      ] }))
      return new Response(JSON.stringify([]))
    }))

    const body = await (await (await route()).GET()).json()
    expect(seen.find(call => call.url.includes('/api/v1/treasury/deals'))).toMatchObject({ authorization: 'Bearer operator-token' })
    expect(seen.find(call => call.url.includes('/api/v1/lending/ledger-backfill/requests'))).toMatchObject({ authorization: 'Bearer operator-token' })
    expect(body.sources.treasury).toBe('ok')
    expect(body.sources['ledger-backfill']).toBe('ok')
    expect(body.items).toEqual([
      { id: 'deal-7', domain: 'treasury', action: 'treasury.MM_PLACEMENT', resourceId: 'deal-7', maker: 'dealer.two', makerActorKind: 'UNKNOWN', proposedAt: '2026-09-20T10:00:00Z' },
      { id: 'request-7', domain: 'ledger-backfill', action: 'lending.ledgerBackfill.decide', resourceId: 'request-7', maker: 'finance.one', makerActorKind: 'UNKNOWN', proposedAt: '2026-09-20T11:00:00Z' },
    ])
  })

  it('does not certify a ledger backfill queue truncated at the history cap', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => String(url).includes('/api/v1/lending/ledger-backfill/requests')
      ? new Response(JSON.stringify({ requests: Array.from({ length: 100 }, (_, i) => ({ id: `request-${i}`, state: 'APPROVED', proposedBy: 'finance', proposedAt: null })) }))
      : new Response(JSON.stringify([]))))
    const body = await (await (await route()).GET()).json()
    expect(body.sources['ledger-backfill']).toBe('unavailable')
  })

  it('reads the durable delegation lifecycle queue instead of claiming it is empty', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/delegations/approvals') && u.includes('state=PROPOSED'))).toBe(true)
    expect(body.sources.delegation).toBe('ok')
  })

  // The regression this file exists to prevent, stated as a test for the transaction slice of
  // issue #5679: transaction-service now serves its pending list and the inbox must read it, or
  // a parked `transaction.reverse` decision is invisible on the one screen built to show them.
  it('reads the transaction queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/transactions/approvals'))).toBe(true)
    expect(body.sources.transaction).toBe('ok')
  })

  // The regression this file exists to prevent, stated as a test: sanctions-service has served
  // its pending list since #3472 and the inbox did not read it, so a parked `sanctions.clear`
  // was invisible on the one screen built to show parked decisions. A source that is silently
  // absent looks exactly like a source with nothing in it.
  it('reads the sanctions queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/sanctions/approvals'))).toBe(true)
    expect(body.sources.sanctions).toBe('ok')
  })

  // Same regression, domestic-payment side (issue #5679): domestic-payment-service has served
  // ApprovalStore.decide since ADR-0155 but never the pending list, so a parked
  // `domestic-payment.transitionStatus` decision was invisible on the one screen built to show
  // parked decisions.
  it('reads the domestic-payment queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/domestic-payments/approvals'))).toBe(true)
    expect(body.sources['domestic-payment']).toBe('ok')
  })

  // Same regression, clearing's side (issue #5679): clearing-service has served
  // ApprovalStore.decide since ADR-0155 but never the pending list, so a parked
  // `clearingBatch.settle`/`clearingBatch.triggerCycle` decision was invisible on the one
  // screen built to show parked decisions.
  it('reads the clearing queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/clearing/approvals'))).toBe(true)
    expect(body.sources.clearing).toBe('ok')
  })

  // Same regression, fx side (issue #5679): fx-service has served ApprovalStore.decide since
  // ADR-0155 but never the pending list, so a parked `fx.convert` four-eyes decision was
  // invisible on the one screen built to show parked decisions.
  it('reads the fx queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/fx/approvals'))).toBe(true)
    expect(body.sources.fx).toBe('ok')
  })

  // Same regression, ledger side (issue #5679): ledger-service has served ApprovalStore.decide
  // since ADR-0155 but never the pending list, so a parked `ledger.reverse` decision was
  // invisible on the one screen built to show parked decisions.
  it('reads the ledger queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/journals/approvals'))).toBe(true)
    expect(body.sources.ledger).toBe('ok')
  })

  // Same regression, swift side (issue #5679): swift-service has served ApprovalStore.decide
  // since ADR-0155 but never the pending list, so a parked `swift.send` four-eyes decision was
  // invisible on the one screen built to show parked decisions.
  it('reads the swift queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/swift/approvals'))).toBe(true)
    expect(body.sources.swift).toBe('ok')
  })

  // Same regression, sepa-payment side (issue #5679): sepa-payment-service has served
  // ApprovalStore.decide since ADR-0155 but never the pending list, so a parked
  // `sepaPayment.transitionStatus` four-eyes decision was invisible on the one screen built to
  // show parked decisions.
  it('reads the sepaPayment queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/sepa-payments/approvals'))).toBe(true)
    expect(body.sources['sepa-payment']).toBe('ok')
  })

  // Same regression, sepa-instant side (issue #5679): sepa-instant-service has served
  // ApprovalStore.decide since ADR-0155 but never the pending list, so a parked
  // `sctInstPayment.recall` four-eyes decision was invisible on the one screen built to show
  // parked decisions.
  it('reads the sepa-instant queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/sepa-instant/approvals'))).toBe(true)
    expect(body.sources['sepa-instant']).toBe('ok')
  })

  // Same regression, notification side (issue #5679): notification-service has served
  // ApprovalStore.decide since ADR-0176 D5 but never the pending list, so a parked
  // `opsmessage.compose` decision was invisible on the one screen built to show parked
  // decisions.
  it('reads the notification queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/notifications/approvals'))).toBe(true)
    expect(body.sources.notification).toBe('ok')
  })

  it('reads the party queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/parties/approvals'))).toBe(true)
    expect(body.sources.party).toBe('ok')
  })

  it('reads the account queue at all — an unread money-path source must never look empty', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/accounts/approvals'))).toBe(true)
    expect(body.sources.account).toBe('ok')
  })

  it('reads the consent queue at all — an unread source is indistinguishable from an empty one', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/consents/approvals'))).toBe(true)
    expect(body.sources.consent).toBe('ok')
  })

  it('reads the balance queue at all — an unread money-path source must never look empty', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      seen.push(String(url))
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()

    expect(seen.some(u => u.includes('/api/v1/balances/approvals'))).toBe(true)
    expect(body.sources.balance).toBe('ok')
  })

  it('relays the operator token to the billing contract and preserves its audit provenance', async () => {
    const seen: Array<{ url: string; authorization: string | null }> = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      seen.push({
        url: String(url),
        authorization: new Headers(init?.headers).get('authorization'),
      })
      const rows = String(url).includes('/api/v1/fees/approvals')
        ? [{
            id: 'BL-1',
            action: 'billing.post',
            resourceId: 'fee-7',
            makerId: 'operator.j',
            createdAt: '2026-07-29T11:57:00Z',
          }]
        : []
      return Promise.resolve(new Response(JSON.stringify(rows), { status: 200 }))
    }))

    const body = await (await (await route()).GET()).json()
    const billingCall = seen.find(call => call.url.includes('/api/v1/fees/approvals'))

    expect(billingCall).toBeDefined()
    const billingUrl = new URL(billingCall!.url)
    expect(billingUrl.pathname).toBe('/api/v1/fees/approvals')
    expect(billingUrl.searchParams.get('limit')).toBe('50')
    expect(billingCall!.authorization).toBe('Bearer operator-token')
    expect(body.sources.billing).toBe('ok')
    expect(body.items).toEqual([{
      id: 'BL-1',
      domain: 'billing',
      action: 'billing.post',
      resourceId: 'fee-7',
      maker: 'operator.j',
      makerActorKind: 'UNKNOWN',
      proposedAt: '2026-07-29T11:57:00Z',
    }])
  })

  it('degrades to the working half when one queue is down', async () => {
    const mock = vi.fn().mockImplementation((url: string) => {
      if (url.includes('lending')) return Promise.reject(new Error('lending down'))
      if (url.includes('sanctions')) return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      if (url.includes('transaction')) return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      if (url.includes('domestic-payment')) return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      if (url.includes('clearing')) return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      if (url.includes('fx/approvals')) return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      if (url.includes('swift')) return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      if (url.includes('sepa-payments/approvals') || url.includes('sepa-payment')) {
        return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      }
      if (url.includes('sepa-instant')) return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      if (url.includes('proposals')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'P-1', suggestedAction: 'agent.research', proposedBy: 'ui-assistant', proposedAt: '2026-07-30T08:00:00Z' },
        ]), { status: 200 }))
      }
      return Promise.resolve(new Response(JSON.stringify([]), { status: 200 })) // ledger
    })
    vi.stubGlobal('fetch', mock)

    const res = await (await route()).GET()
    const body = await res.json()
    expect(res.status).toBe(200)
    expect(body.items).toHaveLength(1)
    expect(body.items[0].domain).toBe('agent')
  })


  it('reports a refused source instead of an empty queue — 403 is ordinary for a non-desk role', async () => {
    const mock = vi.fn().mockImplementation((url: string) =>
      Promise.resolve(
        String(url).includes('lending')
          ? new Response('{}', { status: 403 })
          : new Response(JSON.stringify([]), { status: 200 }),
      ),
    )
    vi.stubGlobal('fetch', mock)

    const res = await (await route()).GET()
    const body = await res.json()

    expect(res.status).toBe(200)
    expect(body.items).toEqual([])
    expect(body.sources.lending).toBe('forbidden')
    expect(body.sources.sanctions).toBe('ok')
    expect(body.sources.transaction).toBe('ok')
    expect(body.sources['domestic-payment']).toBe('ok')
    expect(body.sources.clearing).toBe('ok')
    expect(body.sources.fx).toBe('ok')
    expect(body.sources.ledger).toBe('ok')
    expect(body.sources.swift).toBe('ok')
    expect(body.sources['sepa-payment']).toBe('ok')
    expect(body.sources['sepa-instant']).toBe('ok')
    expect(body.sources.notification).toBe('ok')
    expect(body.sources.party).toBe('ok')
    expect(body.sources.agent).toBe('ok')
  })

  it('marks an unreachable source unavailable, distinct from refused', async () => {
    const mock = vi.fn().mockImplementation((url: string) =>
      String(url).includes('lending')
        ? Promise.reject(new Error('ECONNREFUSED'))
        : Promise.resolve(new Response('{}', { status: 500 })),
    )
    vi.stubGlobal('fetch', mock)

    const body = await (await (await route()).GET()).json()

    expect(body.sources.lending).toBe('unavailable')
    expect(body.sources.sanctions).toBe('unavailable')
    expect(body.sources.transaction).toBe('unavailable')
    expect(body.sources['domestic-payment']).toBe('unavailable')
    expect(body.sources.clearing).toBe('unavailable')
    expect(body.sources.fx).toBe('unavailable')
    expect(body.sources.ledger).toBe('unavailable')
    expect(body.sources.swift).toBe('unavailable')
    expect(body.sources['sepa-payment']).toBe('unavailable')
    expect(body.sources['sepa-instant']).toBe('unavailable')
    expect(body.sources.notification).toBe('unavailable')
    expect(body.sources.party).toBe('unavailable')
    expect(body.sources.agent).toBe('unavailable')
  })

  it('reads the SCA and settlement operator queues with the operator bearer and a bounded page', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const u = String(url)
      if (u.includes('/api/v1/sca/approvals') || u.includes('/api/v1/settlements/approvals')) {
        seen.push(u)
        expect(new Headers(init?.headers).get('authorization')).toBe('Bearer operator-token')
        expect(new URL(u).searchParams.get('limit')).toBe('50')
        return u.includes('/sca/')
          ? Response.json([{ id: 'sca-1', action: 'device.revoke', resourceId: 'party-1', makerId: 'maker.a', makerActorKind: 'CUSTOMER_PARTY', createdAt: '2026-09-13T10:00:00Z', status: 'PENDING', decidedBy: null }])
          : Response.json([{ id: 'set-1', action: 'settlement.create', resourceId: null, makerId: 'maker.b', makerActorKind: 'HUMAN', createdAt: '2026-09-13T11:00:00Z', status: 'PENDING', decidedBy: null }])
      }
      return new Response('nope', { status: 503 })
    }))
    const body = await (await (await route()).GET()).json()
    expect(seen).toHaveLength(2)
    expect(body.sources.sca).toBe('ok')
    expect(body.sources.settlement).toBe('ok')
    expect(body.items).toContainEqual({ id: 'sca-1', domain: 'sca', action: 'device.revoke', resourceId: 'party-1', maker: 'maker.a', makerActorKind: 'CUSTOMER_PARTY', proposedAt: '2026-09-13T10:00:00Z' })
    expect(body.items).toContainEqual({ id: 'set-1', domain: 'settlement', action: 'settlement.create', resourceId: null, maker: 'maker.b', makerActorKind: 'HUMAN', proposedAt: '2026-09-13T11:00:00Z' })
  })

  it('reports a forbidden operator queue (compliance caller) and a full page as not complete', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const u = String(url)
      if (u.includes('/api/v1/sca/approvals')) return new Response('', { status: 403 })
      if (u.includes('/api/v1/settlements/approvals')) {
        return Response.json(Array.from({ length: 50 }, (_, i) => ({ id: `s-${i}`, action: 'settlement.create', resourceId: null, makerId: 'm', createdAt: '2026-09-13T11:00:00Z' })))
      }
      return new Response('nope', { status: 503 })
    }))
    const body = await (await (await route()).GET()).json()
    expect(body.sources.sca).toBe('forbidden')
    expect(body.sources.settlement).toBe('unavailable')
  })
})
