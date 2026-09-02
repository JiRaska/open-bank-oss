// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0227 D2 (phase 1): the federated approval inbox read. One BFF route merges each
// domain's pending queue — lending's, sanctions' and clearing's maker-checker approvals plus
// the agent plane's proposals — into one canonical item shape for the /approvals screen. Read-only by
// design: disposal happens in the governed per-domain decide flows (money-path disposal
// additionally requires SCA, ADR-0227 D4 — that is the phase-2 design, deliberately NOT a
// one-click button here).

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

type SourceState = 'ok' | 'forbidden' | 'unavailable' | 'not-configured'

type InboxItem = {
  id: string
  domain: 'lending' | 'sanctions' | 'transaction' | 'domestic-payment' | 'clearing' | 'fx' | 'ledger' | 'swift' | 'sepa-payment' | 'sepa-instant' | 'notification' | 'party' | 'account' | 'consent' | 'balance' | 'billing' | 'agent'
  action: string
  resourceId: string | null
  maker: string | null
  proposedAt: string | null
}

type LendingApproval = {
  id: string
  action: string
  resourceId: string | null
  makerId: string | null
  createdAt: string | null
}

// sanctions-service serves the same libs `PendingApproval` shape as lending — one contract,
// two producers (ADR-0227 D1). It has served this list since #3472 and the inbox was not
// reading it, so a parked `sanctions.clear` decision stayed invisible on the one screen
// built to show parked decisions.
type SanctionsApproval = LendingApproval

// transaction-service serves the same libs `PendingApproval` shape too (#5679).
type TransactionApproval = LendingApproval
// fx-service serves the same libs `PendingApproval` shape (issue #5679, money-path first per
// that issue's own ordering). Before this, an `fx.convert` four-eyes decision parked at 202
// was discoverable only by whoever had been handed its id out of band.
type FxApproval = LendingApproval

// sepa-instant-service serves the same libs `PendingApproval` shape (issue #5679, money-path
// first per that issue's own ordering). Before this, an `sctInstPayment.recall` four-eyes
// decision parked at 202 was discoverable only by whoever had been handed its id out of band.
type SepaInstantApproval = LendingApproval

// domestic-payment-service serves the same libs `PendingApproval` shape (issue #5679, money-path
// first per that issue's own ordering). Before this, a `domestic-payment.transitionStatus`
// four-eyes decision parked at 202 was discoverable only by whoever had been handed its id out
// of band.
type DomesticPaymentApproval = LendingApproval

// clearing-service serves the same libs `PendingApproval` shape (issue #5679, money-path per
// that issue's own ordering). Before this, a `clearingBatch.settle`/`clearingBatch.triggerCycle`
// four-eyes decision parked at 202 was discoverable only by whoever had been handed its id out
// of band.
type ClearingApproval = LendingApproval

// ledger-service serves the same libs `PendingApproval` shape (issue #5679, money-path first
// per that issue's own ordering). Before this, a `ledger.reverse` four-eyes decision parked at
// 202 was discoverable only by whoever had been handed its id out of band.
type LedgerApproval = LendingApproval

// swift-service serves the same libs `PendingApproval` shape (issue #5679, money-path first
// per that issue's own ordering). Before this, a `swift.send` four-eyes decision parked at 202
// was discoverable only by whoever had been handed its id out of band.
type SwiftApproval = LendingApproval

// sepa-payment-service serves the same libs `PendingApproval` shape (issue #5679, money-path
// first per that issue's own ordering). Before this, a `sepaPayment.transitionStatus` four-eyes
// decision parked at 202 was discoverable only by whoever had been handed its id out of band.
type SepaPaymentApproval = LendingApproval

// notification-service serves the same libs `PendingApproval` shape (issue #5679). Before this,
// an `opsmessage.compose` four-eyes decision parked at 202 was discoverable only by whoever had
// been handed its id out of band.
type NotificationApproval = LendingApproval

// party-service serves the same shared PendingApproval shape. This source turns the approval id
// returned from a parked `party.merge` into a visible checker hand-off before its 24-hour TTL.
type PartyApproval = LendingApproval

// account-service serves the shared PendingApproval shape for gated account lifecycle actions.
// Surfacing it here makes a parked freeze or other protected action discoverable before TTL expiry.
type AccountApproval = LendingApproval

type ConsentApproval = LendingApproval

// balance-service serves the shared PendingApproval shape for gated credit/debit actions.
type BalanceApproval = LendingApproval

// billing-service serves the shared PendingApproval shape for gated fee-posting actions.
// Listing is read-only; posting and reversal controls remain entirely service-owned.
type BillingApproval = LendingApproval
type AgentProposal = {
  id: string
  suggestedAction: string
  proposedBy: string
  proposedAt: string
}

type SourceResult = { items: InboxItem[]; state: SourceState }

/**
 * A refused read must never render as an empty queue. lending's list is
 * @RolesAllowed(LENDING_OFFICER, CREDIT_RISK, ADMIN) while /approvals itself is open to any
 * authenticated operator, so 403 is the ORDINARY outcome for a supervisor or viewer - and
 * "no approvals pending" is the most dangerous thing an approvals screen can say wrongly.
 * The per-source state travels to the client, which shows it.
 */
function stateFor(status: number): SourceState {
  return status === 401 || status === 403 ? 'forbidden' : 'unavailable'
}

async function lendingPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('lending-service', 'lending', 8126, '/api/v1/lending/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as LendingApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'lending' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function sanctionsPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('sanctions-service', 'sanctions', 8123, '/api/v1/sanctions/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as SanctionsApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'sanctions' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function transactionPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('transaction-service', 'transaction', 8102, '/api/v1/transactions/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as TransactionApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'transaction' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function domesticPaymentPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('domestic-payment', 'payments', 8116, '/api/v1/domestic-payments/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as DomesticPaymentApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'domestic-payment' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function clearingPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('clearing-service', 'payments', 8124, '/api/v1/clearing/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as ClearingApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'clearing' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function fxPending(headers: HeadersInit): Promise<SourceResult> {
  // k8s workload is `fx-service` (with the `-service` suffix, unlike sepa-instant) — see
  // src/app/api/svc/[service]/[...path]/route.ts's SERVICE_MAP for the canonical key and
  // openbank-infra/gitops/components/fx-service/fx-service.yaml for the `fx` namespace.
  // fx-service also sits on the FinOps off-hours scaledown allowlist (see app/api/fx/rates'
  // discovery-based handling), so a scaled-to-zero fx-service surfaces here as 'unavailable'
  // (a fetch failure caught below), same as any other down source — the inbox does not need
  // to distinguish scale-to-zero from genuinely down.
  const res = await fetch(serverSvcUrl('fx-service', 'fx', 8119, '/api/v1/fx/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as FxApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'fx' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function ledgerPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('ledger-service', 'ledger', 8101, '/api/v1/journals/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as LedgerApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'ledger' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function swiftPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('swift-service', 'payments', 8122, '/api/v1/swift/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as SwiftApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'swift' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function sepaPaymentPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('sepa-payment', 'payments', 8115, '/api/v1/sepa-payments/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as SepaPaymentApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'sepa-payment' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function sepaInstantPending(headers: HeadersInit): Promise<SourceResult> {
  // k8s workload is `sepa-instant` (no `-service` suffix) — see the same footgun documented in
  // app/payments/page.tsx (a `sepa-instant-service` key missed and pinned that panel to
  // `not_deployed`).
  const res = await fetch(serverSvcUrl('sepa-instant', 'payments', 8127, '/api/v1/sepa-instant/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as SepaInstantApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'sepa-instant' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function notificationPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('notification-service', 'notifications', 8112, '/api/v1/notifications/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as NotificationApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'notification' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function partyPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('party-service', 'party', 8111, '/api/v1/parties/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as PartyApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'party' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function accountPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('account-service', 'accounts', 8100, '/api/v1/accounts/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as AccountApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'account' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function consentPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('consent-service', 'consent', 8106, '/api/v1/consents/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as ConsentApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'consent' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function balancePending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('balance-service', 'balances', 8103, '/api/v1/balances/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as BalanceApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'balance' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

async function billingPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(serverSvcUrl('billing-service', 'billing', 8132, '/api/v1/fees/approvals', { limit: '50' }), {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as BillingApproval[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'billing' as const, action: r.action,
      resourceId: r.resourceId, maker: r.makerId, proposedAt: r.createdAt,
    })),
  }
}

function agentBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://openbank-agent-service:8109'
  return (process.env.AGENT_SERVICE_URL ?? 'http://localhost:8109/mcp').replace(/\/mcp$/, '')
}

async function agentPending(headers: HeadersInit): Promise<SourceResult> {
  const res = await fetch(`${agentBase()}/api/v1/proposals?state=proposed`, {
    headers, signal: AbortSignal.timeout(4000), cache: 'no-store',
  })
  if (!res.ok) return { items: [], state: stateFor(res.status) }
  const rows = (await res.json()) as AgentProposal[]
  return {
    state: 'ok',
    items: rows.map(r => ({
      id: r.id, domain: 'agent' as const, action: r.suggestedAction,
      resourceId: null, maker: r.proposedBy, proposedAt: r.proposedAt,
    })),
  }
}

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const headers = { authorization: `Bearer ${session.user.accessToken}` }
  const unavailable: SourceResult = { items: [], state: 'unavailable' }
  const [lending, sanctions, transaction, domesticPayment, clearing, fx, ledger, swift, sepaPayment, sepaInstant, notification, party, account, consent, balance, billing, agent] = await Promise.all([
    lendingPending(headers).catch(() => unavailable),
    sanctionsPending(headers).catch(() => unavailable),
    transactionPending(headers).catch(() => unavailable),
    domesticPaymentPending(headers).catch(() => unavailable),
    clearingPending(headers).catch(() => unavailable),
    fxPending(headers).catch(() => unavailable),
    ledgerPending(headers).catch(() => unavailable),
    swiftPending(headers).catch(() => unavailable),
    sepaPaymentPending(headers).catch(() => unavailable),
    sepaInstantPending(headers).catch(() => unavailable),
    notificationPending(headers).catch(() => unavailable),
    partyPending(headers).catch(() => unavailable),
    accountPending(headers).catch(() => unavailable),
    consentPending(headers).catch(() => unavailable),
    balancePending(headers).catch(() => unavailable),
    billingPending(headers).catch(() => unavailable),
    agentPending(headers).catch(() => unavailable),
  ])
  const items = [...lending.items, ...sanctions.items, ...transaction.items, ...domesticPayment.items, ...clearing.items, ...fx.items, ...ledger.items, ...swift.items, ...sepaPayment.items, ...sepaInstant.items, ...notification.items, ...party.items, ...account.items, ...consent.items, ...balance.items, ...billing.items, ...agent.items]
    .sort((a, b) => (a.proposedAt ?? '').localeCompare(b.proposedAt ?? ''))
  return NextResponse.json({
    items,
    sources: {
      lending: lending.state,
      sanctions: sanctions.state,
      transaction: transaction.state,
      'domestic-payment': domesticPayment.state,
      clearing: clearing.state,
      fx: fx.state,
      ledger: ledger.state,
      swift: swift.state,
      'sepa-payment': sepaPayment.state,
      'sepa-instant': sepaInstant.state,
      notification: notification.state,
      party: party.state,
      account: account.state,
      consent: consent.state,
      balance: balance.state,
      billing: billing.state,
      agent: agent.state,
    },
  })
}
