// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { z } from 'zod'

export const scaApprovalSchema = z.object({
  id: z.uuid(),
  action: z.string(),
  resourceId: z.string().nullable(),
  status: z.enum(['PENDING', 'APPROVED', 'REJECTED', 'EXECUTED']),
  makerId: z.string().min(1),
  createdAt: z.string(),
  decidedBy: z.string().nullable(),
})
export type ScaApproval = z.infer<typeof scaApprovalSchema>

export function approvalTarget(approval: ScaApproval): { party?: string; target: string; fingerprint: boolean } | null {
  const resource = approval.resourceId ?? ''
  if (approval.action === 'scaChallenge.consume') {
    return z.uuid().safeParse(resource).success ? { target: resource, fingerprint: false } : null
  }
  const [party, target, extra] = resource.split('@')
  if (extra !== undefined || !z.uuid().safeParse(party).success || !target) return null
  if (approval.action === 'device.revoke' && z.uuid().safeParse(target).success) {
    return { party, target, fingerprint: false }
  }
  if (approval.action === 'device.enroll' && /^[a-f0-9]{64}$/.test(target)) {
    return { party, target, fingerprint: true }
  }
  return null
}
