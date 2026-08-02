// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { forwardToSanctionsService } from '@/lib/sanctions/upstream'

export const dynamic = 'force-dynamic'

/**
 * Trigger a refresh of one sanctions list. `id` here is the listType (e.g. OFAC_SDN), not a uuid.
 *
 * Moved onto the shared helper in #3334, same reason as the sibling PUT: it hand-rolled its fetch
 * and sent no `Authorization`, so an operator's manual refresh 401'd against a healthy service.
 * Keeps the longer 15s budget — a refresh fetches and parses a real upstream feed.
 */
export async function POST(_req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  return forwardToSanctionsService(
    `/api/v1/sanctions/lists/${encodeURIComponent(id)}/refresh`,
    'POST',
    undefined,
    15_000,
  )
}
