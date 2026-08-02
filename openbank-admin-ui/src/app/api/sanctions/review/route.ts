// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { forwardToSanctionsService } from '@/lib/sanctions/upstream'

export const dynamic = 'force-dynamic'

/**
 * Manual disposition of a screening hit (issue #3334).
 *
 * `POST /api/v1/sanctions/review` had no caller anywhere in the product: no route here, no UI
 * control, and no M2M client (all six SanctionsServiceClient interfaces declare only /screen).
 * So a HIT/POTENTIAL_HIT could not be cleared, whitelisted or escalated without hand-crafting a
 * request against api.open-bank.tech, while the screen rendered a "Pending Review" queue with no
 * way to work it. 5AMLD Art. 13 manual review, and per rules.yaml the highest-risk action in that
 * service.
 *
 * Four-eyes (ADR-0155): the upstream answers 202 + {status, approvalId} when OPA flags
 * `sanctions.clear` as four_eyes_required. That is a normal outcome, not a failure — 202 is
 * `res.ok`, so the helper forwards the body untouched and the client can show the approval id.
 * The maker then retries this route with `X-Approval-Id`, which MUST reach the upstream: the
 * interceptor reads it off the request, and without it every retry mints a fresh approval and
 * answers 202 again — an infinite loop in which each individual call looks healthy.
 */
export async function POST(req: NextRequest) {
  const body = await req.json()

  // Allow-list, not a pass-through of req.headers: forwarding the browser's header set to an
  // OIDC-gated backend is how a BFF leaks cookies or lets a caller override Authorization.
  const approvalId = req.headers.get('x-approval-id')
  const extraHeaders = approvalId ? { 'X-Approval-Id': approvalId } : undefined

  return forwardToSanctionsService('/api/v1/sanctions/review', 'POST', body, 10_000, extraHeaders)
}
