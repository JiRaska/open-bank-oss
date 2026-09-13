// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasRole, ROLES } from '@/lib/auth/roles'

export const dynamic = 'force-dynamic'

// This is deliberately not a generic agent proxy. The route names one bounded
// operator action and one in-cluster service, so a browser session cannot turn
// Admin UI into an arbitrary cluster relay.
function flakyTestHunterBase(): string {
  return (process.env.FLAKY_TEST_HUNTER_URL ?? 'http://localhost:8148').replace(/\/$/, '')
}

type AdmissionCause = 'timeout' | 'network' | 'upstream_timeout' | 'conflict' | 'upstream_status' | 'unexpected_status'

function admissionOutcomeUnknown(
  cause: AdmissionCause,
  requestedOn: string,
  status: 502 | 504,
  upstreamStatus?: number,
) {
  return NextResponse.json({
    error: 'admission_outcome_unknown',
    cause,
    requestedOn,
    ...(upstreamStatus === undefined ? {} : { upstreamStatus }),
  }, { status })
}

function utcDay(now: Date, offsetDays = 0): string {
  const instant = new Date(now)
  instant.setUTCDate(instant.getUTCDate() + offsetDays)
  return instant.toISOString().slice(0, 10)
}

async function requestedUtcDay(request: Request): Promise<string | null> {
  const body = await request.json().catch(() => null) as { requestedOn?: unknown } | null
  if (!body || typeof body.requestedOn !== 'string') return null
  const requestedOn = body.requestedOn.trim()
  const now = new Date()
  return requestedOn === utcDay(now) || requestedOn === utcDay(now, -1) ? requestedOn : null
}

export async function POST(request: Request) {
  const session = await auth()
  const accessToken = session?.user?.accessToken
  if (!accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  if (!hasRole(session.user.roles ?? [], ROLES.ADMIN)) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }
  const requestedOn = await requestedUtcDay(request)
  if (!requestedOn) {
    return NextResponse.json({ error: 'invalid_idempotency_day' }, { status: 400 })
  }
  const idempotencyKey = `flaky-test-hunter-operator-manual-${requestedOn}`

  try {
    const upstream = await fetch(`${flakyTestHunterBase()}/api/v1/flaky-test-hunter/check/trigger-async-idempotent`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Idempotency-Key': idempotencyKey,
      },
      // The endpoint only asks Temporal to start the durable workflow and returns
      // 202. It must not wait for a fleet scan/LLM diagnosis, which can take minutes.
      signal: AbortSignal.timeout(10_000),
      cache: 'no-store',
    })
    if (upstream.status === 404) {
      // A pre-expand backend does not expose the idempotent route. Never fall back to the legacy
      // trigger: across UTC midnight it would ignore requestedOn and could start a second workflow.
      return NextResponse.json({
        error: 'idempotent_admission_not_supported',
        requestedOn,
        upstreamStatus: upstream.status,
      }, { status: 503 })
    }
    if (upstream.status !== 202) {
      // Only the contracted 202 proves admission. Preserve a bounded cause so monitoring can
      // distinguish contract drift, timeout, conflict and upstream failure without leaking bodies.
      if (upstream.ok) {
        return admissionOutcomeUnknown('unexpected_status', requestedOn, 502, upstream.status)
      }
      if (upstream.status === 408) {
        return admissionOutcomeUnknown('upstream_timeout', requestedOn, 504, upstream.status)
      }
      if (upstream.status === 409) {
        return admissionOutcomeUnknown('conflict', requestedOn, 502, upstream.status)
      }
      if (upstream.status >= 500) {
        return admissionOutcomeUnknown('upstream_status', requestedOn, 502, upstream.status)
      }
      return NextResponse.json({ error: 'admission_rejected', upstreamStatus: upstream.status }, { status: upstream.status })
    }
    const body = await upstream.json().catch(() => null) as { workflowId?: unknown } | null
    const expectedWorkflowId = `flaky-test-hunter-check-operator_manual-${requestedOn}`
    if (!body || typeof body.workflowId !== 'string' || body.workflowId.trim() !== expectedWorkflowId) {
      // Admission was accepted, but a missing or mismatched handle cannot prove that it targeted
      // the requested idempotency day. Preserve the recovery key and fail closed.
      return NextResponse.json({
        error: 'admission_accepted_handle_unknown',
        cause: 'invalid_response',
        requestedOn,
      }, { status: 502 })
    }
    return NextResponse.json({ workflowId: expectedWorkflowId }, { status: 202 })
  } catch (error: unknown) {
    const name = error && typeof error === 'object' && 'name' in error ? String(error.name) : ''
    const timedOut = name === 'AbortError' || name === 'TimeoutError'
    // Fetch cannot tell whether a connection failed before or after dispatch. The caller may retry
    // with requestedOn because the service maps that key to the same Temporal workflow id.
    return admissionOutcomeUnknown(timedOut ? 'timeout' : 'network', requestedOn, timedOut ? 504 : 502)
  }
}
