// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

// Retain the old BFF route as an explicit, non-mutating tombstone. Calling the synchronous
// backend trigger after an ambiguous response could start a second sweep; the governed IAOps
// route owns idempotent admission and mixed-version recovery now. The backend legacy endpoint
// remains available to an older Admin UI image during rollback, but this image never calls it.
export async function POST() {
  return NextResponse.json({
    error: 'legacy_trigger_retired',
    replacement: '/api/iaops/flaky-test-hunter/trigger',
  }, { status: 410 })
}
