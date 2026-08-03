// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { forwardToSanctionsService } from '@/lib/sanctions/upstream'

export const dynamic = 'force-dynamic'

// The upstream clamps to 1..200; parse to a NUMBER here rather than forwarding the raw string.
// Two reasons, and the second is why CodeQL was right to flag the first draft of this file:
//   1. `?limit=abc` should not reach the service at all — it is an integer in the spec.
//   2. `path` is interpolated into the helper's console.error, so a raw query value would be a
//      caller-controlled string reaching a log format string (js/tainted-format-string, high) and
//      a newline-injection vector into the log stream (js/log-injection). A parsed integer cannot
//      carry either. Sanitising at the log call would have treated the symptom; refusing to build
//      a path out of unvalidated input removes the taint at the source.
function parseLimit(raw: string | null): number | null {
  if (raw === null) return null
  const n = Number.parseInt(raw, 10)
  if (!Number.isFinite(n)) return null
  return Math.min(Math.max(n, 1), 200)
}

/**
 * The checker's queue (issue #3472).
 *
 * #3465 shipped the decide flow as an id field with "paste what someone hands you", because
 * sanctions-service served no list — a decision parked at 202 was invisible to everyone and the
 * 24h Redis TTL expired it silently. This is the read half.
 */
export async function GET(req: NextRequest) {
  const limit = parseLimit(req.nextUrl.searchParams.get('limit'))
  const path = limit === null
    ? '/api/v1/sanctions/approvals'
    : `/api/v1/sanctions/approvals?limit=${limit}`
  return forwardToSanctionsService(path)
}
