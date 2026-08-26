// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

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
    const response = await fetch(`${referralServiceUrl}/api/v1/referrals/programs`, {
      headers: { authorization: `Bearer ${session.user.accessToken}` },
      signal: AbortSignal.timeout(4000),
      cache: 'no-store',
    })
    if (!response.ok) {
      const state = response.status === 401 || response.status === 403
        ? 'unauthorized'
        : response.status === 404
          ? 'not_deployed'
          : 'unreachable'
      return NextResponse.json({ items: [], state })
    }
    return NextResponse.json({ items: await response.json(), state: 'ok' })
  } catch {
    return NextResponse.json({ items: [], state: 'unreachable' })
  }
}
