// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Publishing a program concept (ADR-0310 four-eyes). The checker is taken from the token
// server-side — nothing about the approver travels in this request, so the maker/checker
// comparison can never be fed a caller-supplied value on both sides.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

const referralServiceUrl = process.env.REFERRAL_SERVICE_URL ??
  (process.env.KUBERNETES_SERVICE_HOST
    ? 'https://referral-service.referral.svc:8443'
    : 'http://localhost:8155')

// Same UUID shape the rest of the console validates against (campaigns/new/page.tsx). A malformed
// id never reaches the service — it cannot be a program id, so forwarding it only produces a
// confusing 404 out of a client-side typo.
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

export async function POST(_req: Request, ctx: { params: Promise<{ id: string }> }) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const { id } = await ctx.params
  if (!UUID_RE.test(id)) {
    return NextResponse.json({ error: 'invalid program id' }, { status: 400 })
  }
  try {
    const response = await fetch(`${referralServiceUrl}/api/v1/referrals/programs/${encodeURIComponent(id)}/publish`, {
      method: 'POST',
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(8000),
      cache: 'no-store',
    })
    const text = await response.text()
    const payload = text ? JSON.parse(text) : {}
    if (response.ok) {
      return NextResponse.json({ state: 'ok', ...payload })
    }
    if (response.status === 401 || response.status === 403) {
      return NextResponse.json({ state: 'unauthorized' }, { status: 200 })
    }
    if (response.status === 400 || response.status === 409 || response.status === 404) {
      // The service answers 409 with the domain invariant that blocked the transition — "maker
      // cannot publish their own program", "program is not a draft" — and that sentence is the
      // entire value of the response. Collapsing it to "rejected" would make a four-eyes refusal
      // indistinguishable from an outage.
      const message = typeof payload?.error === 'string' ? payload.error : text
      return NextResponse.json({ state: 'rejected', message }, { status: 200 })
    }
    return NextResponse.json({ state: 'unreachable' }, { status: 200 })
  } catch {
    return NextResponse.json({ state: 'unreachable' }, { status: 200 })
  }
}
