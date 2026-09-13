// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { forwardToSanctionsService } from '@/lib/sanctions/upstream'

export const dynamic = 'force-dynamic'

export async function GET() {
  return forwardToSanctionsService('/api/v1/sanctions/lists')
}

export async function POST() {
  // Refreshing every enabled list re-downloads and re-indexes the upstream feeds — since #9048
  // the v2 endpoint answers 202 immediately and the scheduler runs the imports off the request
  // path, so the BFF no longer needs to hold a long upstream request open.
  return forwardToSanctionsService('/api/v2/sanctions/lists/refresh-all', 'POST', {}, 20_000, {
    // #9048: the v2 contract requires an Idempotency-Key on money-path POSTs (#8351). A fresh
    // key per operator click is correct here — the flag update is idempotent by construction,
    // so a retry with the same or a new key converges to the same state.
    'Idempotency-Key': crypto.randomUUID(),
  })
}
