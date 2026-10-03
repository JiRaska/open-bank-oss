// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Durable operator approvals served by sca-service (#11903) and settlement-service (#11915).
// Both services expose GET /approvals, GET /approvals/{id} and PATCH /approvals/{id} with
// {"approve": boolean}; the checker is the caller's principal and must differ from the maker.

import { z } from 'zod'

export type OperatorApprovalDomain = 'sca' | 'settlement'

export const OPERATOR_APPROVAL_DOMAINS: readonly OperatorApprovalDomain[] = ['sca', 'settlement']

const status = z.enum(['PENDING', 'APPROVED', 'REJECTED', 'EXECUTED'])

/** The shape both services answer; settlement adds evidence fields on GET /{id}. */
export const operatorApprovalSchema = z.object({
  id: z.uuid(),
  action: z.string().min(1),
  resourceId: z.string().nullable().optional().transform(value => value ?? null),
  status,
  makerId: z.string().nullable().optional().transform(value => value ?? null),
  createdAt: z.string().nullable().optional().transform(value => value ?? null),
  decidedBy: z.string().nullable().optional().transform(value => value ?? null),
  decidedAt: z.string().nullable().optional(),
  expiresAt: z.string().nullable().optional(),
  expired: z.boolean().optional(),
  summary: z.string().nullable().optional(),
})
export type OperatorApproval = z.infer<typeof operatorApprovalSchema>

export function isApprovalId(id: string): boolean {
  return z.uuid().safeParse(id).success
}

export type ApprovalTarget =
  | { kind: 'challenge'; challenge: string }
  | { kind: 'device'; party: string }
  | { kind: 'settlement'; summary: string }

/**
 * What the checker reviews, parsed strictly from what the service exposes. Anything that does not
 * match a known shape returns null and the workbench offers no decision: a checker must never
 * decide a request this UI cannot show.
 *
 * sca-service binds `device.enroll`/`device.revoke` to the PARTY (`@Authorize(resource = "#partyId")`)
 * and the exact device to a server-side request fingerprint it does not publish; the queue therefore
 * shows the party, and the binding — not this screen — stops a retry from targeting another device.
 * `scaChallenge.consume` binds the challenge id itself. settlement-service publishes a redacted
 * `summary` of the bound instruction on GET /approvals/{id}; without one there is nothing to review.
 */
export function approvalTarget(domain: OperatorApprovalDomain, approval: OperatorApproval): ApprovalTarget | null {
  if (domain === 'settlement') {
    if (approval.action !== 'settlement.create') return null
    const summary = approval.summary?.trim()
    return summary ? { kind: 'settlement', summary } : null
  }
  const resource = approval.resourceId ?? ''
  if (!isApprovalId(resource)) return null
  if (approval.action === 'scaChallenge.consume') return { kind: 'challenge', challenge: resource }
  if (approval.action === 'device.enroll' || approval.action === 'device.revoke') return { kind: 'device', party: resource }
  return null
}

/**
 * The maker may not decide their own request; the backend refuses with 403. The UI does not
 * invite it either. An unknown viewer (unreadable token) or unknown maker never hides the
 * controls — the server stays the control.
 */
export function isOwnRequest(approval: Pick<OperatorApproval, 'makerId'>, viewer: string | null): boolean {
  return Boolean(viewer && approval.makerId && approval.makerId === viewer)
}

export function approvalApiPath(domain: OperatorApprovalDomain, id: string): string {
  const base = domain === 'sca' ? '/api/sca/approvals' : '/api/settlements/approvals'
  return `${base}/${encodeURIComponent(id)}`
}
