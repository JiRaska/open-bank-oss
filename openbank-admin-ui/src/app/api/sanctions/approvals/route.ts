// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { forwardToSanctionsService } from '@/lib/sanctions/upstream'

export const dynamic = 'force-dynamic'

/**
 * The checker's queue (issue #3472).
 *
 * #3465 shipped the decide flow as an id field with "paste what someone hands you", because
 * sanctions-service served no list — a decision parked at 202 was invisible to everyone and the
 * 24h Redis TTL expired it silently. This is the read half.
 *
 * `limit` is clamped upstream too (200); passing it through rather than hard-coding keeps the
 * clamp in one place, on the server that owns the store.
 */
export async function GET(req: NextRequest) {
  const limit = req.nextUrl.searchParams.get('limit')
  const path = limit
    ? `/api/v1/sanctions/approvals?limit=${encodeURIComponent(limit)}`
    : '/api/v1/sanctions/approvals'
  return forwardToSanctionsService(path)
}
