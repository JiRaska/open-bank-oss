// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Read-only campaign console (#2895). Authoring — the wizard, templates, the four-eyes
// submit — is ADR-0221 and deliberately NOT here: `submit` → `activate`-by-a-different-approver
// is designed for two people at a screen, and half of it behind a button is worse than none.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { svcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const headers = { authorization: `Bearer ${session.user.accessToken}` }
  try {
    const res = await fetch(svcUrl('campaign-service', '/api/v1/campaigns'), {
      headers,
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!res.ok) {
      // A refused or failed read must not render as "no campaigns" — that reads as a quiet
      // estate, which is the wrong conclusion to draw from an authorization error.
      return NextResponse.json(
        { items: [], state: res.status === 401 || res.status === 403 ? 'unauthorized' : 'unreachable' },
        { status: 200 },
      )
    }
    return NextResponse.json({ items: await res.json(), state: 'ok' })
  } catch {
    return NextResponse.json({ items: [], state: 'unreachable' }, { status: 200 })
  }
}
