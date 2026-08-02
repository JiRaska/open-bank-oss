// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { forwardToSanctionsService } from '@/lib/sanctions/upstream'

export const dynamic = 'force-dynamic'

/**
 * Checker half of the four-eyes gate on `sanctions.clear` (ADR-0155, issue #3334).
 *
 * Without this route the ceremony is half-built: the maker could submit a review from the UI and
 * then wait on a checker who has no way to decide except curl. `ApprovalResource.decide` is a
 * PATCH, and the self-approval guard lives in `RedisApprovalStore.decide` — a DIFFERENT principal
 * must decide, enforced server-side, so this route deliberately adds no check of its own (see
 * #3349 for the fact that guard has no test feeding it a maker approving themselves).
 *
 * A maker deciding their own request gets 403 from the shared `SelfApprovalNotAllowedMapper`,
 * which the helper surfaces as `upstream_error` with the status preserved.
 */
export async function PATCH(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await req.json()
  return forwardToSanctionsService(
    `/api/v1/sanctions/approvals/${encodeURIComponent(id)}`,
    'PATCH',
    body,
  )
}
