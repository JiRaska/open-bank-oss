// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { forwardToSanctionsService } from '@/lib/sanctions/upstream'

export const dynamic = 'force-dynamic'

export async function POST(req: NextRequest) {
  const body = await req.json()
  // A screening run matches the name against every enabled list — give it more headroom
  // than a plain read.
  //
  // v3, not the deprecated v1: v1 only knows the original seven lists and answers 400
  // "CZ_TERRORISTS requires /api/v3/sanctions/screen" when the operator selects the Czech national
  // list. The v3 request body is a superset of v1's, so the client payload is unchanged.
  return forwardToSanctionsService('/api/v3/sanctions/screen', 'POST', body, 15_000)
}
