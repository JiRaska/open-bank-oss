// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

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

export type DomainApprovalItem = {
  id: string
  domain: ApprovalDomain
  action: string
  resourceId: string | null
  maker: string | null
  makerActorKind?: 'HUMAN' | 'AI_AGENT' | 'SERVICE_ACCOUNT' | 'CUSTOMER_PARTY' | 'UNKNOWN'
  proposedAt: string | null
}

export type ApprovalSortOrder = 'oldest' | 'newest'

export function filterAndSortDomainApprovals(
  items: readonly DomainApprovalItem[],
  domain: ApprovalDomain | 'all',
  order: ApprovalSortOrder,
  query = '',
): DomainApprovalItem[] {
  const needle = query.trim().toLocaleLowerCase()
  const filtered = items.filter(item => {
    if (domain !== 'all' && item.domain !== domain) return false
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
