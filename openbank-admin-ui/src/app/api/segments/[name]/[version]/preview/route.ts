// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Cohort size for one segment version. The service runs the SAME evaluation enrolment runs, so a
// preview can differ from an actual send only by time — which `asOf` carries (ADR-0201 D1).

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

export async function GET(
  _req: Request,
  { params }: { params: Promise<{ name: string; version: string }> },
) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const { name, version } = await params
  const headers = { authorization: `Bearer ${session.user.accessToken}` }
  const path = `/api/v1/segments/${encodeURIComponent(name)}/${encodeURIComponent(version)}/preview`
  try {
    const res = await fetch(serverSvcUrl('campaign-service', 'campaign', 8128, path), {
      headers,
      signal: AbortSignal.timeout(8000),
      cache: 'no-store',
    })
    if (!res.ok) {
      // A failed preview must not render as a cohort of zero — "nobody matches" is a business
      // answer a marketer would act on, and it is not what a 403 or a timeout means.
      return NextResponse.json(
        {
          state:
            res.status === 401 || res.status === 403
              ? 'unauthorized'
              : res.status === 404
                ? 'unknown_segment'
                : 'unreachable',
        },
        { status: 200 },
      )
    }
    return NextResponse.json({ ...(await res.json()), state: 'ok' })
  } catch {
    return NextResponse.json({ state: 'unreachable' }, { status: 200 })
  }
}
