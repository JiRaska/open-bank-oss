// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { forwardToSanctionsService } from '@/lib/sanctions/upstream'

export const dynamic = 'force-dynamic'

/**
 * Enable/disable a sanctions list, or patch its cron/source URL. Operator-mutating against an
 * OIDC-gated upstream.
 *
 * Moved onto the shared helper in #3334. #3336 fixed `checks`, `lists` and `screen`, but this
 * route and `lists/[id]/refresh` kept their own fetch and their own missing `Authorization`
 * header — the same defect, on the same screen, still live. The old `catch` also returned the
 * upstream error message verbatim, which #3336 removed everywhere else (ADR-0080 P1).
 */
export async function PUT(req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  const body = await req.json()
  return forwardToSanctionsService(`/api/v1/sanctions/lists/${encodeURIComponent(id)}`, 'PUT', body)
}
