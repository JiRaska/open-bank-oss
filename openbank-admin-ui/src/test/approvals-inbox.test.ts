// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Integration tests for the federated approval inbox read (/api/approvals/pending, ADR-0227 D2).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({
  auth: vi.fn(),
}))

import { auth } from '@/auth'

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
    expect(body.items.map((i: { id: string }) => i.id)).toEqual(['L-1', 'N-1', 'D-1', 'C-1', 'J-1', 'W-1', 'SP-1', 'F-1', 'I-1', 'PT-1', 'A-1', 'CO-1', 'B-1', 'S-1', 'P-1', 'T-1', 'L-2'])
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
    expect(body.items[13]).toMatchObject({ domain: 'sanctions', action: 'sanctions.clear', maker: 'analyst.c' })
    expect(body.items[14]).toMatchObject({ domain: 'agent', action: 'agent.research' })
    expect(body.items[15]).toMatchObject({ domain: 'transaction', action: 'transaction.reverse', maker: 'teller.d' })
    expect(body.sources.party).toBe('ok')
    expect(body.sources.account).toBe('ok')
    expect(body.sources.balance).toBe('ok')
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
})
