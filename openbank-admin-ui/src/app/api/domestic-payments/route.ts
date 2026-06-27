// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// BFF for domestic payments (ADR-0080 P1, pentest FIND-S3-03/04/08). The browser must NEVER
// reach the backend directly. List (GET) and submit (POST) are proxied here, server-side,
// session-gated, with the operator's bearer token. Client errors are generic; details log
// server-side only.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'

// In-cluster ClusterIP (payments namespace). Overridable via env for local/dev.
const DOMESTIC_SERVICE = process.env.DOMESTIC_SERVICE_URL || 'http://domestic-payment.payments.svc:8116'

export const dynamic = 'force-dynamic'

export async function GET() {
  try {
    const session = await auth()
    if (!session?.user?.accessToken) {
      return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
    }
    const res = await fetch(`${DOMESTIC_SERVICE}/api/v1/domestic-payments`, {
      headers: { 'Authorization': `Bearer ${session.user.accessToken}` },
      cache: 'no-store',
      signal: AbortSignal.timeout(8000),
    })
    if (!res.ok) {
      return NextResponse.json({ error: 'Upstream error' }, { status: res.status })
    }
    return new NextResponse(await res.arrayBuffer(), {
      status: res.status,
      headers: { 'Content-Type': res.headers.get('Content-Type') || 'application/json' },
    })
  } catch (error) {
    console.error('domestic-payments GET failed:', error)
    return NextResponse.json({ error: 'An internal error occurred' }, { status: 502 })
  }
}

export async function POST(req: NextRequest) {
  try {
    const session = await auth()
    if (!session?.user?.accessToken) {
      return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
    }

    const payload = await req.json()
    const idempotencyKey = req.headers.get('Idempotency-Key') || crypto.randomUUID()

    const res = await fetch(`${DOMESTIC_SERVICE}/api/v1/domestic-payments`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${session.user.accessToken}`,
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(8000),
    })

    if (!res.ok) {
      const errorText = await res.text()
      return new NextResponse(errorText, {
        status: res.status,
        headers: { 'Content-Type': res.headers.get('Content-Type') || 'text/plain' },
      })
    }

    return new NextResponse(await res.arrayBuffer(), {
      status: res.status,
      headers: { 'Content-Type': res.headers.get('Content-Type') || 'application/json' },
    })
  } catch (error) {
    console.error('domestic-payments POST failed:', error)
    return NextResponse.json({ error: 'An internal error occurred' }, { status: 502 })
  }
}
