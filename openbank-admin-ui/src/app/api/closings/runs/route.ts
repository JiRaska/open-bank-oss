// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Closings BFF (ADR-0069 D3): recent EoM close runs + the manual catch-up trigger.
// Session-gated server-side relay to statement-service — see lib/closings/upstream.

import { NextRequest } from 'next/server'
import { forwardToStatementService } from '@/lib/closings/upstream'

export const dynamic = 'force-dynamic'

const MAX_LIMIT = 100

/** GET /api/closings/runs?limit=N — recent close runs, newest first (bounded). */
export async function GET(req: NextRequest) {
  const raw = Number(req.nextUrl.searchParams.get('limit') ?? '20')
  const limit = Number.isFinite(raw) ? Math.min(Math.max(Math.trunc(raw), 1), MAX_LIMIT) : 20
  return forwardToStatementService(`/api/v1/statements/close-runs?limit=${limit}`)
}

/** POST /api/closings/runs — trigger a manual catch-up close pass (idempotent upstream). */
export async function POST() {
  // Server-side closings:run gate (ADMIN/OPERATOR) — the UI hide is cosmetic.
  return forwardToStatementService('/api/v1/statements/close-runs', 'POST', 'closings:run')
}
