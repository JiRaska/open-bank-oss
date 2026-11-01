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

function isPublishedCatalogue(body: unknown): body is ReferralProgramReference[] {
  if (!Array.isArray(body)) return false
  return body.every(item => {
    if (!item || typeof item !== 'object') return false
    const reference = item as Partial<ReferralProgramReference>
    return typeof reference.id === 'string' && reference.id.length > 0 &&
      typeof reference.name === 'string' && reference.name.trim().length > 0 &&
      Number.isInteger(reference.version) && (reference.version ?? 0) > 0
  })
}

/**
 * Create a DRAFT program concept (ADR-0310). Marketing authors it; publishing is a different
 * person's action, exactly like campaign submit → activate above.
 *
 * Only the fields the service contract declares are forwarded — anything else in the body is
 * dropped here, so a caller cannot smuggle a field the service would silently accept.
 */
const CREATE_FIELDS = ['name', 'version', 'rewardAmount', 'currency', 'qualifyingEvent', 'attributionWindowEndsAt'] as const

export async function POST(req: Request) {
  const session = await auth()
  if (!session?.user?.accessToken) {
    return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  }
  const incoming: unknown = await req.json().catch(() => ({}))
  const body: Record<string, unknown> = {}
  if (incoming && typeof incoming === 'object') {
    for (const field of CREATE_FIELDS) {
      if (field in incoming) body[field] = (incoming as Record<string, unknown>)[field]
    }
  }
  try {
    const response = await fetch(`${referralServiceUrl}/api/v1/referrals/programs`, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${session.user.accessToken}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(8000),
      cache: 'no-store',
    })
    const text = await response.text()
    const payload = text ? JSON.parse(text) : {}
    if (response.status === 201) {
      return NextResponse.json({ state: 'ok', ...payload })
    }
    if (response.status === 401 || response.status === 403) {
      return NextResponse.json({ state: 'unauthorized' }, { status: 200 })
    }
    if (response.status === 400 || response.status === 409) {
      // A refused draft carries its reason (a regex violation, a duplicate name@version), and
      // that sentence is what tells the author what to change — flattening it to "rejected"
      // would make a validation failure indistinguishable from a four-eyes conflict.
      const message = typeof payload?.error === 'string' ? payload.error : text
      return NextResponse.json({ state: 'rejected', message }, { status: 200 })
    }
    return NextResponse.json({ state: 'unreachable' }, { status: 200 })
  } catch {
    return NextResponse.json({ state: 'unreachable' }, { status: 200 })
  }
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
    // The admin edge is a data-minimising contract boundary. Even if an upstream regression adds
    // Referral-owned reward, qualification, or party fields, Campaign Studio receives only its
    // immutable selection reference.
    const items = body.map(({ id, name, version }) => ({ id, name, version }))
    return NextResponse.json({ items, state: 'ok' })
  } catch {
    return NextResponse.json({ items: [], state: 'unreachable' })
  }
}
