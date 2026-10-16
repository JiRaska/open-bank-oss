// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// DRAFT referral-program concepts awaiting an operator's publish (ADR-0310 four-eyes). The
// published catalogue (../route.ts) is the immutable, customer-facing projection; this is the
// approval queue that gives the publish button something real to act on.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

const referralServiceUrl = process.env.REFERRAL_SERVICE_URL ??
  (process.env.KUBERNETES_SERVICE_HOST
    ? 'https://referral-service.referral.svc:8443'
    : 'http://localhost:8155')

export async function GET() {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  try {
    const response = await fetch(`${referralServiceUrl}/api/v1/referrals/programs/drafts`, {
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!response.ok) {
      // Same refusal semantics as the published catalogue: a denied or missing drafts read must
      // not render as "no drafts". The page hides the section on 'unauthorized' — a pure
      // campaign:view persona (e.g. an auditor) has no business in the approval queue.
      const state = response.status === 401 || response.status === 403
        ? 'unauthorized'
        : response.status === 404
          ? 'not_deployed'
          : 'unreachable'
      return NextResponse.json({ items: [], state })
    }
    const body: unknown = await response.json()
    if (!Array.isArray(body)) return NextResponse.json({ items: [], state: 'unreachable' })
    return NextResponse.json({ items: body, state: 'ok' })
  } catch {
    return NextResponse.json({ items: [], state: 'unreachable' }, { status: 200 })
  }
}
