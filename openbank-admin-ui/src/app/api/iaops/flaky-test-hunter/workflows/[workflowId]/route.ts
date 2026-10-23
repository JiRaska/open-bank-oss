// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasRole, ROLES } from '@/lib/auth/roles'

export const dynamic = 'force-dynamic'

const STATUS_VALUES = new Set([
  'RUNNING', 'COMPLETED', 'FAILED', 'CANCELED', 'TERMINATED',
  'CONTINUED_AS_NEW', 'TIMED_OUT', 'UNSPECIFIED', 'UNRECOGNIZED',
])
const WORKFLOW_ID = /^flaky-test-hunter-check-operator_manual-(\d{4}-\d{2}-\d{2})$/

function flakyTestHunterBase(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://flaky-test-hunter.flaky-test-hunter.svc:8148'
  }
  return (process.env.FLAKY_TEST_HUNTER_URL ?? 'http://localhost:8148').replace(/\/$/, '')
}

function allowedWorkflowId(value: string): boolean {
  const match = WORKFLOW_ID.exec(value)
  if (!match) return false
  const day = match[1]
  const parsed = new Date(`${day}T00:00:00.000Z`)
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== day) return false
  const today = new Date().toISOString().slice(0, 10)
  const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10)
  return day === today || day === yesterday
}

export async function GET(_request: Request, context: { params: Promise<{ workflowId: string }> }) {
  const session = await auth()
  const accessToken = session?.user?.accessToken
  if (!accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  if (!hasRole(session.user.roles ?? [], ROLES.ADMIN)) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }

  const { workflowId } = await context.params
  if (!allowedWorkflowId(workflowId)) return NextResponse.json({ error: 'invalid_workflow_id' }, { status: 400 })

  try {
    const upstream = await fetch(
      `${flakyTestHunterBase()}/api/v1/flaky-test-hunter/check/workflows/${encodeURIComponent(workflowId)}`,
      {
        headers: { Authorization: `Bearer ${accessToken}` },
        signal: AbortSignal.timeout(5_000),
        cache: 'no-store',
      },
    )
    if (upstream.status === 404) return NextResponse.json({ error: 'not_found' }, { status: 404 })
    if (upstream.status === 400) return NextResponse.json({ error: 'invalid_workflow_id' }, { status: 400 })
    if (upstream.status === 401 || upstream.status === 403) return NextResponse.json({ error: 'forbidden' }, { status: upstream.status })
    if (!upstream.ok) return NextResponse.json({ error: 'workflow_status_unavailable' }, { status: 502 })

    const body = await upstream.json().catch(() => null) as { workflowId?: unknown; status?: unknown } | null
    if (!body || body.workflowId !== workflowId || typeof body.status !== 'string' || !STATUS_VALUES.has(body.status)) {
      return NextResponse.json({ error: 'invalid_workflow_status_response' }, { status: 502 })
    }
    return NextResponse.json({ workflowId, status: body.status }, { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json({ error: 'workflow_status_unavailable' }, { status: 502 })
  }
}
