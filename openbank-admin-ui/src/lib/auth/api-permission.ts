// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { auth } from '@/auth'
import { hasPermission, type Permission } from '@/lib/auth/roles'

type ApiPermissionDecision =
  | { ok: true }
  | { ok: false; status: 401 | 403; error: 'unauthorized' | 'forbidden' }

/**
 * Enforce an admin-ui permission at the BFF route boundary.
 *
 * Edge middleware and client-side AuthGuard keep navigation truthful, but neither
 * protects a direct call to an internal `/api/**` route. Routes use this decision
 * to retain their own established response envelope while sharing one session and
 * permission check. A denial is returned before any upstream request is made.
 */
export async function requireApiPermission(permission: Permission): Promise<ApiPermissionDecision> {
  const session = await auth()
  if (!session?.user) return { ok: false, status: 401, error: 'unauthorized' }
  if (!hasPermission(session.user.roles ?? [], permission)) {
    return { ok: false, status: 403, error: 'forbidden' }
  }
  return { ok: true }
}
