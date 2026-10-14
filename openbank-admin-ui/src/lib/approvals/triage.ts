// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import type { ApprovalRiskClass } from '@/lib/approvals/risk'

export type ApprovalDomain =
  | 'lending'
  | 'sanctions'
  | 'transaction'
  | 'domestic-payment'
  | 'clearing'
  | 'fx'
  | 'ledger'
  | 'swift'
  | 'sepa-payment'
  | 'sepa-instant'
  | 'notification'
  | 'party'
  | 'account'
  | 'consent'
  | 'balance'
  | 'billing'
  | 'delegation'
  | 'communication'
  | 'treasury'
  | 'ledger-backfill'
  | 'compliance-pack'
  | 'campaign'
  | 'audience'
  | 'identity-case'
  | 'sca'
  | 'settlement'

export type DomainApprovalItem = {
  id: string
  domain: ApprovalDomain
  action: string
  resourceId: string | null
  maker: string | null
  makerActorKind?: 'HUMAN' | 'AI_AGENT' | 'SERVICE_ACCOUNT' | 'CUSTOMER_PARTY' | 'UNKNOWN'
  proposedAt: string | null
  // ADR-0227 D1 canonical projection fields (D3 filters). All optional: items federated from
  // an older BFF or a source that predates the projection simply lack the facet, and a facet
  // filter then excludes them rather than guessing.
  summary?: string | null
  amount?: string | null
  currency?: string | null
  riskClass?: ApprovalRiskClass
  payloadRef?: string | null
}

export type ApprovalSortOrder = 'oldest' | 'newest'

// ADR-0227 D3 filter facets. Amount bands compare the numeric amount in plain units,
// regardless of currency — bands exist to triage volume, not to mix currencies. An item with
// no amount (every source today: the maker's request body is not persisted) sits outside
// every band, so selecting a band hides it rather than mis-bucketing it as zero.
export type ApprovalAmountBand = 'lt1k' | '1k-10k' | '10k-100k' | 'gt100k'
// Age bands from proposedAt: under 4 hours, 4h–1d, 1–7 days, older than 7 days. Items without
// a timestamp (e.g. audience proposals) sit outside every band.
export type ApprovalAgeBand = 'lt4h' | '4h-1d' | '1d-7d' | 'gt7d'

export type ApprovalFilters = {
  amountBand?: ApprovalAmountBand | 'all'
  riskClass?: ApprovalRiskClass | 'all'
  ageBand?: ApprovalAgeBand | 'all'
  /** Injectable clock for tests; defaults to Date.now(). */
  now?: Date
}

const AMOUNT_BAND_UPPER: Record<Exclude<ApprovalAmountBand, 'gt100k'>, number> = {
  'lt1k': 1_000,
  '1k-10k': 10_000,
  '10k-100k': 100_000,
}

function amountInBand(amount: string, band: ApprovalAmountBand): boolean {
  const value = Number(amount)
  if (!Number.isFinite(value)) return false
  if (band === 'gt100k') return value > 100_000
  const upper = AMOUNT_BAND_UPPER[band]
  // 'lt1k' has no lower bound; the other two start at their naming floor.
  const lower = band === 'lt1k' ? Number.NEGATIVE_INFINITY : upper / 10
  return value >= lower && value < upper
}

function ageInBand(proposedAt: string, band: ApprovalAgeBand, nowMs: number): boolean {
  const ageMs = nowMs - Date.parse(proposedAt)
  if (!Number.isFinite(ageMs) || ageMs < 0) return false
  const FOUR_HOURS = 4 * 3_600_000
  const ONE_DAY = 24 * 3_600_000
  const SEVEN_DAYS = 7 * ONE_DAY
  switch (band) {
    case 'lt4h': return ageMs < FOUR_HOURS
    case '4h-1d': return ageMs >= FOUR_HOURS && ageMs < ONE_DAY
    case '1d-7d': return ageMs >= ONE_DAY && ageMs < SEVEN_DAYS
    case 'gt7d': return ageMs >= SEVEN_DAYS
  }
}

export function filterAndSortDomainApprovals(
  items: readonly DomainApprovalItem[],
  domain: ApprovalDomain | 'all',
  order: ApprovalSortOrder,
  query = '',
  filters: ApprovalFilters = {},
): DomainApprovalItem[] {
  const needle = query.trim().toLocaleLowerCase()
  const { amountBand = 'all', riskClass = 'all', ageBand = 'all', now } = filters
  const nowMs = (now ?? new Date()).getTime()
  const filtered = items.filter(item => {
    if (domain !== 'all' && item.domain !== domain) return false
    if (amountBand !== 'all') {
      // No amount is never "a small amount": the facet only matches items that carry one.
      if (item.amount == null || !amountInBand(item.amount, amountBand)) return false
    }
    if (riskClass !== 'all' && item.riskClass !== riskClass) return false
    if (ageBand !== 'all') {
      if (item.proposedAt == null || !ageInBand(item.proposedAt, ageBand, nowMs)) return false
    }
    if (!needle) return true
    return [item.id, item.domain, item.action, item.resourceId, item.maker]
      .filter((value): value is string => Boolean(value))
      .some(value => value.toLocaleLowerCase().includes(needle))
  })

  return [...filtered].sort((left, right) => {
    const comparison = (left.proposedAt ?? '').localeCompare(right.proposedAt ?? '')
    return order === 'oldest' ? comparison : -comparison
  })
}

/**
 * Only return routes backed by a real checker UI. The unified inbox remains read-only: these
 * links hand the opaque approval id to the domain that already owns RBAC, self-approval and
 * maker-checker enforcement.
 */
export function approvalWorkbenchHref(item: DomainApprovalItem): string | null {
  if (item.domain === 'sanctions') {
    return `/sanctions?approvalId=${encodeURIComponent(item.id)}#sanctions-approval-id`
  }
  if (item.domain === 'notification') {
    return `/notifications?approvalId=${encodeURIComponent(item.id)}#notification-approval-id`
  }
  if (item.domain === 'delegation') {
    return `/approvals/delegation/${encodeURIComponent(item.id)}`
  }
  if (item.domain === 'sca') {
    return `/approvals/sca/${encodeURIComponent(item.id)}`
  }
  if (item.domain === 'settlement') {
    return `/approvals/settlement/${encodeURIComponent(item.id)}`
  }
  if (item.domain === 'communication') {
    return '/approvals/communication'
  }
  if (item.domain === 'treasury') {
    return `/treasury/deals/${encodeURIComponent(item.id)}`
  }
  if (item.domain === 'ledger-backfill') {
    return '/balance-sheet/ledger-backfill'
  }
  if (item.domain === 'compliance-pack') {
    return '/lending/compliance-packs'
  }
  if (item.domain === 'campaign') {
    return `/campaigns/${encodeURIComponent(item.id)}`
  }
  if (item.domain === 'audience') {
    return '/segments'
  }
  if (item.domain === 'identity-case') {
    return '/identity-cases'
  }
  return null
}

export function readApprovalId(search: string): string | null {
  const approvalId = new URLSearchParams(search).get('approvalId')?.trim()
  return approvalId || null
}
