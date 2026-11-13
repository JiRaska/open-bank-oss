// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { forwardToSanctionsService } from '@/lib/sanctions/upstream'

export const dynamic = 'force-dynamic'

/**
 * Queue a refresh of one sanctions list. `id` here is the listType (e.g. OFAC_SDN), not a uuid.
 *
 * #14780: calls the v2 trigger, which answers 202 at once and flags the list for the scheduler;
 * the v1 route imported the feed inside the request and a large feed outlived any timeout. The
 * UI therefore shows the list as QUEUED (its `refreshRequestedAt`), never as freshly downloaded.
 * Moved onto the shared helper in #3334 so the operator's bearer is relayed.
 */
export async function POST(_req: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  return forwardToSanctionsService(
    `/api/v2/sanctions/lists/${encodeURIComponent(id)}/refresh`,
    'POST',
    {},
    10_000,
    {
      // The v2 contract requires an Idempotency-Key (#8351). A fresh key per click is correct:
      // flagging is idempotent by construction, so any retry converges to the same state.
      'Idempotency-Key': crypto.randomUUID(),
    },
  )
}
