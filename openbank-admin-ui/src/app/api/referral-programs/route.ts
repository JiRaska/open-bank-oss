// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'

export const dynamic = 'force-dynamic'

const referralServiceUrl = process.env.REFERRAL_SERVICE_URL ??
  (process.env.KUBERNETES_SERVICE_HOST
    ? 'https://referral-service.referral.svc:8443'
    : 'http://localhost:8155')

interface ReferralProgramReference {
  id: string
  name: string
  version: number
}

function isPublishedCatalogue(body: unknown): body is { items: ReferralProgramReference[] } {
  if (!body || typeof body !== 'object' || !Array.isArray((body as { items?: unknown }).items)) return false
  return (body as { items: unknown[] }).items.every(item => {
    if (!item || typeof item !== 'object') return false
    const reference = item as Partial<ReferralProgramReference>
    return typeof reference.id === 'string' && reference.id.length > 0 &&
      typeof reference.name === 'string' && reference.name.trim().length > 0 &&
      Number.isInteger(reference.version) && (reference.version ?? 0) > 0
  })
}

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
    const body: unknown = await response.json()
    if (!isPublishedCatalogue(body)) return NextResponse.json({ items: [], state: 'unreachable' })
    return NextResponse.json({ items: body.items, state: 'ok' })
  } catch {
    return NextResponse.json({ items: [], state: 'unreachable' })
  }
}
