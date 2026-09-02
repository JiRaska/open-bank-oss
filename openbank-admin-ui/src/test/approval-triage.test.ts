// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import {
  approvalWorkbenchHref,
  filterAndSortDomainApprovals,
  readApprovalId,
  type DomainApprovalItem,
} from '@/lib/approvals/triage'

const rows: DomainApprovalItem[] = [
  { id: 'balance-old', domain: 'balance', action: 'balance.debit', resourceId: 'account-7', maker: 'maker-a', proposedAt: '2026-08-31T08:00:00Z' },
  { id: 'sanctions-new', domain: 'sanctions', action: 'sanctions.clear', resourceId: 'check-7', maker: 'maker-b', proposedAt: '2026-08-31T10:00:00Z' },
  { id: 'notification-mid', domain: 'notification', action: 'opsmessage.compose', resourceId: null, maker: 'maker-c', proposedAt: '2026-08-31T09:00:00Z' },
  { id: 'billing-newest', domain: 'billing', action: 'fee.post', resourceId: 'fee-7', maker: 'maker-d', proposedAt: '2026-08-31T11:00:00Z' },
  { id: 'delegation-opaque/id', domain: 'delegation', action: 'delegation.reinstate', resourceId: 'grant-7', maker: 'maker-e', proposedAt: '2026-08-31T12:00:00Z' },
]

describe('approval inbox triage', () => {
  it('filters by domain and makes queue ordering explicit', () => {
    expect(filterAndSortDomainApprovals(rows, 'all', 'oldest').map(row => row.id)).toEqual([
      'balance-old', 'notification-mid', 'sanctions-new', 'billing-newest', 'delegation-opaque/id',
    ])
    expect(filterAndSortDomainApprovals(rows, 'all', 'newest').map(row => row.id)).toEqual([
      'delegation-opaque/id', 'billing-newest', 'sanctions-new', 'notification-mid', 'balance-old',
    ])
    expect(filterAndSortDomainApprovals(rows, 'sanctions', 'oldest').map(row => row.id)).toEqual(['sanctions-new'])
    expect(filterAndSortDomainApprovals(rows, 'all', 'oldest', 'MAKER-C').map(row => row.id)).toEqual(['notification-mid'])
    expect(rows.map(row => row.id)).toEqual([
      'balance-old', 'sanctions-new', 'notification-mid', 'billing-newest', 'delegation-opaque/id',
    ])
  })

  it('routes only to checker workbenches that preserve domain governance', () => {
    expect(approvalWorkbenchHref(rows[1])).toBe('/sanctions?approvalId=sanctions-new#sanctions-approval-id')
    expect(approvalWorkbenchHref(rows[2])).toBe('/notifications?approvalId=notification-mid#notification-approval-id')
    expect(approvalWorkbenchHref(rows[4])).toBe('/approvals/delegation/delegation-opaque%2Fid')
    expect(approvalWorkbenchHref(rows[0])).toBeNull()
    expect(approvalWorkbenchHref(rows[3])).toBeNull()
  })

  it('round-trips opaque approval ids and ignores absent deep links', () => {
    const opaque = { ...rows[1], id: 'approval / with?reserved&chars' }
    expect(approvalWorkbenchHref(opaque)).toBe('/sanctions?approvalId=approval%20%2F%20with%3Freserved%26chars#sanctions-approval-id')
    expect(readApprovalId('?approvalId=approval%20%2F%20with%3Freserved%26chars')).toBe('approval / with?reserved&chars')
    expect(readApprovalId('?tab=checks')).toBeNull()
    expect(readApprovalId('?approvalId=%20%20')).toBeNull()
  })
})
