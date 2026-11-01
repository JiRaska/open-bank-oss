// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { revisionApprovalComplete, revisionApprovalPermission } from '@/lib/revision-approval'
import type { RevisionApprovalPolicy, RevisionApprovalResponse } from '@/lib/product-catalog-v2'

const policy: RevisionApprovalPolicy = {
  roles: [{ code: 'LEGAL_COUNSEL' }, { code: 'PRODUCT_OWNER' }],
  requireEffectiveFrom: true,
  requiredAttributes: {},
}
const legal: RevisionApprovalResponse = { role: 'LEGAL_COUNSEL', digest: 'a'.repeat(64), approvedAt: '2026-10-10T00:00:00Z' }
const product: RevisionApprovalResponse = { role: 'PRODUCT_OWNER', digest: 'a'.repeat(64), approvedAt: '2026-10-10T01:00:00Z' }

describe('revision approval policy', () => {
  it('requires every declared role on one digest of the saved draft', () => {
    expect(revisionApprovalComplete(policy, null, true)).toBe(false)
    expect(revisionApprovalComplete(policy, [legal], true)).toBe(false)
    expect(revisionApprovalComplete(policy, [legal, { ...product, digest: 'b'.repeat(64) }], true)).toBe(false)
    expect(revisionApprovalComplete(policy, [legal, product], false)).toBe(false)
    expect(revisionApprovalComplete(policy, [legal, product], true)).toBe(true)
  })

  it('reads roles from the policy, not a fixed vocabulary', () => {
    const safety: RevisionApprovalPolicy = { roles: [{ code: 'SAFETY_OFFICER' }], requireEffectiveFrom: false, requiredAttributes: {} }
    expect(revisionApprovalComplete(safety, [legal, product], true)).toBe(false)
    expect(revisionApprovalComplete(safety, [{ ...legal, role: 'SAFETY_OFFICER' }], true)).toBe(true)
  })

  it('treats a schema without a policy as maker-checker only', () => {
    expect(revisionApprovalComplete(undefined, null, false)).toBe(true)
  })

  it('maps known role codes to console permissions and leaves unknown ones to the server', () => {
    expect(revisionApprovalPermission('LEGAL_COUNSEL')).toBe('catalog:revision-approve:LEGAL_COUNSEL')
    expect(revisionApprovalPermission('SAFETY_OFFICER')).toBeNull()
  })
})
