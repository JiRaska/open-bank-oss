// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0232 presentation helpers shared by the delegation list and detail screens. Kept in
// src/components (not beside the pages) so the i18n and graceful-state guard tests, which scan
// src/components/**, actually cover this file — a helper parked in src/app/ next to its page is
// invisible to both.

'use client'

import { StatusBadge } from '@/components/ui'

/** Mirrors delegation-service's DelegationResponse (rest/dto/DelegationDtos.kt). */
export type Money = { amount: number; currency: string }

export type Grant = {
  id: string
  grantorPartyId: string
  granteePartyId: string
  /**
   * Counterparty display names snapshotted onto the grant at offer time (issue #3604). Null on
   * grants offered before delegation-service carried the field, which is why every render goes
   * through counterpartyLabel() rather than reading these directly.
   */
  grantorName?: string | null
  granteeName?: string | null
  resourceType: string
  resourceId: string
  capabilities: string[]
  approvalPolicy?: string | null
  requiredApprovals?: number | null
  perTransactionLimit?: Money | null
  dailyLimit?: Money | null
  monthlyLimit?: Money | null
  validFrom?: string | null
  validTo?: string | null
  status: string
  note?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  closedAt?: string | null
  closedReason?: string | null
}

export function grantCounterparty(grant: Grant, direction: 'granted' | 'received') {
  return direction === 'granted'
    ? { id: grant.granteePartyId, name: counterpartyLabel(grant.granteeName) }
    : { id: grant.grantorPartyId, name: counterpartyLabel(grant.grantorName) }
}

/**
 * OFFERED is the one delegation status the shared tone map cannot get right by default: it is
 * in-flight (awaiting the grantee's SCA-bound acceptance), not terminal, so it renders as a
 * warning here per that map's own "pass an explicit tone" instruction. Every other status takes
 * the shared default deliberately — ACTIVE success, SUSPENDED danger, and REVOKED / EXPIRED /
 * DECLINED / RENOUNCED neutral, because a customer withdrawing rights is not an incident.
 */
export function DelegationStatusBadge({ status }: { status: string }) {
  return <StatusBadge status={status} tone={status === 'OFFERED' ? 'warning' : undefined} />
}

/**
 * Capability enums are rendered verbatim, not prettified into prose. They are the contract
 * vocabulary shared with the OPA policy and the product-service projections, so an operator
 * comparing this screen against a policy decision or a log line must see the same token.
 */
export function capabilityLabels(capabilities: string[] | undefined): string {
  if (!capabilities?.length) return '—'
  return capabilities.join(', ')
}

/**
 * The label for a counterparty chip (issue #3604).
 *
 * Returns `undefined`, never a blank or a placeholder, when the grant carries no snapshotted
 * name — that is the signal EntityChip needs in order to fall back to its own resolution and
 * then to a shortened id. An empty string would render as a chip with no text at all, which
 * looks like a name that failed to load rather than one that was never captured.
 *
 * A whitespace-only name is treated as absent for the same reason.
 */
export function counterpartyLabel(name: string | null | undefined): string | undefined {
  const trimmed = name?.trim()
  return trimmed ? trimmed : undefined
}

/** A ceiling of `null` means UNCAPPED for that window — never render it as zero. */
export function formatCeiling(limit: Money | null | undefined, locale = 'en-GB'): string {
  if (!limit || typeof limit.amount !== 'number') return '—'
  return `${limit.amount.toLocaleString(locale)} ${limit.currency}`
}
