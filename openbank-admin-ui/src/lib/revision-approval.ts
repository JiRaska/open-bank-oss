// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { PERMISSIONS, type Permission } from '@/lib/auth/roles'
import type { RevisionApprovalPolicy, RevisionApprovalResponse } from '@/lib/product-catalog-v2'

/**
 * Every role the schema's pack policy declares has approved the same digest. The server returns
 * only approvals matching the current saved draft, so an edited editor is never complete.
 */
export function revisionApprovalComplete(
  policy: RevisionApprovalPolicy | undefined,
  approvals: RevisionApprovalResponse[] | null,
  editorMatchesSaved: boolean,
): boolean {
  if (!policy) return true
  if (!editorMatchesSaved || approvals === null) return false
  const matched = policy.roles.map(role => approvals.find(item => item.role === role.code))
  if (matched.some(item => !item)) return false
  return new Set(matched.map(item => item!.digest)).size === 1
}

/**
 * Console permission that reveals the approve control for a pack-declared role. A code the console
 * has no mapping for returns null: the control is shown and product-catalog alone decides.
 */
export function revisionApprovalPermission(code: string): Permission | null {
  const key = `catalog:revision-approve:${code}`
  return key in PERMISSIONS ? key as Permission : null
}
