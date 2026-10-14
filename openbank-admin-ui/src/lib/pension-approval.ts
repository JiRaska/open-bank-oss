// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { PensionApprovalResponse } from '@/lib/product-catalog-v2'

/** The server returns only approvals matching the current saved draft digest. */
export function pensionApprovalComplete(
  approvals: PensionApprovalResponse[] | null,
  editorMatchesSaved: boolean,
): boolean {
  if (!editorMatchesSaved || approvals === null) return false
  const legal = approvals.find(item => item.role === 'LEGAL_COUNSEL')
  const product = approvals.find(item => item.role === 'PRODUCT_OWNER')
  return Boolean(legal && product && legal.digest === product.digest)
}
