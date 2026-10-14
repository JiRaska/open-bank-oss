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

  it('filters by risk class derived from rules.yaml action-class metadata', () => {
    const withRisk: DomainApprovalItem[] = [
      { ...rows[0], riskClass: 'MONEY_PATH' },
      { ...rows[1], riskClass: 'DESTRUCTIVE' },
      { ...rows[2], riskClass: 'OPERATIONAL' },
    ]
    expect(filterAndSortDomainApprovals(withRisk, 'all', 'oldest', '', { riskClass: 'MONEY_PATH' }).map(r => r.id))
      .toEqual(['balance-old'])
    expect(filterAndSortDomainApprovals(withRisk, 'all', 'oldest', '', { riskClass: 'DESTRUCTIVE' }).map(r => r.id))
      .toEqual(['sanctions-new'])
    expect(filterAndSortDomainApprovals(withRisk, 'all', 'oldest', '', { riskClass: 'OPERATIONAL' }).map(r => r.id))
      .toEqual(['notification-mid'])
    // Items without a risk facet (older BFF snapshot) survive 'all' but match no specific class.
    expect(filterAndSortDomainApprovals(rows, 'all', 'oldest', '', { riskClass: 'MONEY_PATH' })).toEqual([])
    expect(filterAndSortDomainApprovals(rows, 'all', 'oldest')).toHaveLength(rows.length)
  })

  it('filters by amount band and treats an absent amount as outside every band', () => {
    const withAmounts: DomainApprovalItem[] = [
      { ...rows[0], amount: '500', currency: 'CZK' },
      { ...rows[1], amount: '5000', currency: 'CZK' },
      { ...rows[2], amount: '50000', currency: 'CZK' },
      { ...rows[3], amount: '500000', currency: 'CZK' },
      { ...rows[4] }, // no amount — never "a small amount"
    ]
    const band = (amountBand: 'lt1k' | '1k-10k' | '10k-100k' | 'gt100k') =>
      filterAndSortDomainApprovals(withAmounts, 'all', 'oldest', '', { amountBand }).map(r => r.id)
    expect(band('lt1k')).toEqual(['balance-old'])
    expect(band('1k-10k')).toEqual(['sanctions-new'])
    expect(band('10k-100k')).toEqual(['notification-mid'])
    expect(band('gt100k')).toEqual(['billing-newest'])
    // A non-numeric amount sits outside every band, same as an absent one.
    const junk = withAmounts.map(r => ({ ...r, amount: r.amount ?? 'n/a' }))
    expect(filterAndSortDomainApprovals(junk, 'all', 'oldest', '', { amountBand: 'lt1k' }).map(r => r.id))
      .toEqual(['balance-old'])
  })

  it('filters by request age against an injectable clock, with untimestamped items outside every band', () => {
    const now = new Date('2026-08-31T12:00:00Z')
    const aged: DomainApprovalItem[] = [
      { ...rows[0], proposedAt: '2026-08-31T10:00:00Z' },   // 2h old
      { ...rows[1], proposedAt: '2026-08-31T00:00:00Z' },   // 12h old
      { ...rows[2], proposedAt: '2026-08-25T12:00:00Z' },   // 6d
      { ...rows[3], proposedAt: '2026-08-20T12:00:00Z' },   // 11d
      { ...rows[4], proposedAt: null },                      // no timestamp
    ]
    const band = (ageBand: 'lt4h' | '4h-1d' | '1d-7d' | 'gt7d') =>
      filterAndSortDomainApprovals(aged, 'all', 'oldest', '', { ageBand, now }).map(r => r.id)
    expect(band('lt4h')).toEqual(['balance-old'])
    expect(band('4h-1d')).toEqual(['sanctions-new'])
    expect(band('1d-7d')).toEqual(['notification-mid'])
    expect(band('gt7d')).toEqual(['billing-newest'])
    // Boundary: exactly 4h is '4h-1d', exactly 1d is '1d-7d', exactly 7d is 'gt7d'.
    const boundary: DomainApprovalItem[] = [
      { ...rows[0], proposedAt: '2026-08-31T08:00:00Z' }, // exactly 4h
      { ...rows[1], proposedAt: '2026-08-30T12:00:00Z' }, // exactly 1d
      { ...rows[2], proposedAt: '2026-08-24T12:00:00Z' }, // exactly 7d
    ]
    expect(filterAndSortDomainApprovals(boundary, 'all', 'oldest', '', { ageBand: 'lt4h', now })).toEqual([])
    expect(filterAndSortDomainApprovals(boundary, 'all', 'oldest', '', { ageBand: '4h-1d', now }).map(r => r.id))
      .toEqual(['balance-old'])
    expect(filterAndSortDomainApprovals(boundary, 'all', 'oldest', '', { ageBand: '1d-7d', now }).map(r => r.id))
      .toEqual(['sanctions-new'])
    expect(filterAndSortDomainApprovals(boundary, 'all', 'oldest', '', { ageBand: 'gt7d', now }).map(r => r.id))
      .toEqual(['notification-mid'])
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

  it('hands treasury and ledger backfill to their governed checker pages', () => {
    expect(approvalWorkbenchHref({ ...rows[0], domain: 'treasury', id: 'deal/7' })).toBe('/treasury/deals/deal%2F7')
    expect(approvalWorkbenchHref({ ...rows[0], domain: 'ledger-backfill', id: 'request-7' })).toBe('/balance-sheet/ledger-backfill')
  })

  it('hands specialist four-eyes queues to their existing workbenches', () => {
    expect(approvalWorkbenchHref({ ...rows[0], domain: 'compliance-pack', id: 'pack-7' })).toBe('/lending/compliance-packs')
    expect(approvalWorkbenchHref({ ...rows[0], domain: 'campaign', id: 'campaign/7' })).toBe('/campaigns/campaign%2F7')
    expect(approvalWorkbenchHref({ ...rows[0], domain: 'audience', id: 'newcomers@3' })).toBe('/segments')
    expect(approvalWorkbenchHref({ ...rows[0], domain: 'identity-case', id: 'case-7' })).toBe('/identity-cases')
  })
})
