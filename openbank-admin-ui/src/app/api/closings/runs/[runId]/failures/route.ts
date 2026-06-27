// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Closings BFF (ADR-0069 D3): per-pocket failures recorded within one close run.

import { NextRequest, NextResponse } from 'next/server'
import { forwardToStatementService } from '@/lib/closings/upstream'

export const dynamic = 'force-dynamic'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export async function GET(
  _req: NextRequest,
  { params }: { params: Promise<{ runId: string }> },
) {
  const { runId } = await params
  // Validate before we call (search & pagination rule): never relay free text
  // into a typed upstream path segment.
  if (!UUID_RE.test(runId)) {
    return NextResponse.json({ error: 'invalid_run_id' }, { status: 400 })
  }
  return forwardToStatementService(`/api/v1/statements/close-runs/${runId}/failures`)
}
