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

export type DomainApprovalItem = {
  id: string
  domain: ApprovalDomain
  action: string
  resourceId: string | null
  maker: string | null
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
  return null
}

export function readApprovalId(search: string): string | null {
  const approvalId = new URLSearchParams(search).get('approvalId')?.trim()
  return approvalId || null
}
