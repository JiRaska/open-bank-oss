// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

/** The live platform guardrails shown in Studio; never a locally copied policy default. */
export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  try {
    const res = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, '/api/v1/campaigns/guardrails'), {
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!res.ok) {
      return NextResponse.json({ guardrails: null, state: res.status === 401 || res.status === 403 ? 'unauthorized' : 'unreachable' })
    }
    return NextResponse.json({ guardrails: await res.json(), state: 'ok' })
  } catch {
    return NextResponse.json({ guardrails: null, state: 'unreachable' })
  }
}
