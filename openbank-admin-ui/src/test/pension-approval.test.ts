// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { pensionApprovalComplete } from '@/lib/pension-approval'
import type { PensionApprovalResponse } from '@/lib/product-catalog-v2'

const legal: PensionApprovalResponse = { role: 'LEGAL_COUNSEL', digest: 'a'.repeat(64), approvedAt: '2026-10-10T00:00:00Z' }
const product: PensionApprovalResponse = { role: 'PRODUCT_OWNER', digest: 'a'.repeat(64), approvedAt: '2026-10-10T01:00:00Z' }

describe('pension publication readiness', () => {
  it('fails closed until both roles approve the same saved revision', () => {
    expect(pensionApprovalComplete(null, true)).toBe(false)
    expect(pensionApprovalComplete([legal], true)).toBe(false)
    expect(pensionApprovalComplete([legal, { ...product, digest: 'b'.repeat(64) }], true)).toBe(false)
    expect(pensionApprovalComplete([legal, product], false)).toBe(false)
    expect(pensionApprovalComplete([legal, product], true)).toBe(true)
  })
})
