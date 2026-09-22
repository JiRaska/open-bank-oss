// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'

export const dynamic = 'force-dynamic'

// BFF proxy to HolmesGPT /api/chat (ADR-0088 Davis-lite, ADR-0031 D9 read-only
// oversight agent). The admin-UI calls this endpoint with a plain-text alert
// description; this route forwards it to the in-cluster HolmesGPT service and
// returns the RCA text. This route performs its own session + permission check:
// a client-side AuthGuard is presentation only and cannot protect a direct API request.
// HolmesGPT stays in-cluster only (no ingress).
//
// Timeout: 300s mirrors the relay timeout. NVIDIA NIM 8B takes ~30-60s for a
// full tool-loop RCA (Prometheus + k8s state fetch + LLM); 300s is a safe cap.

function holmesBase(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://holmesgpt-holmes.observability.svc:80'
  }
  return process.env.HOLMES_URL ?? 'http://localhost:18080'
}

function caseCoordinatorBase(): string {
  if (process.env.SERVICES_HOST === 'container') {
    return 'http://case-coordinator-agent.platform.svc:8146'
  }
  return (process.env.CASE_COORDINATOR_URL ?? 'http://localhost:8146').replace(/\/$/, '')
}

function extractRca(body: unknown): string {
  if (typeof body === 'string') return body.slice(0, 8000)
  if (body && typeof body === 'object') {
    const d = body as Record<string, unknown>
    for (const k of ['analysis', 'response', 'text', 'answer', 'result']) {
      if (typeof d[k] === 'string' && d[k]) return (d[k] as string).slice(0, 8000)
    }
    return JSON.stringify(body).slice(0, 8000)
  }
  return String(body).slice(0, 8000)
}

type ShadowCaseResult =
  | { recorded: true; caseId: string }
  | { recorded: false; reason: 'not_authorized' | 'unavailable' }

async function alertFingerprint(ask: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(ask.trim()))
  return Array.from(new Uint8Array(digest))
    .map(byte => byte.toString(16).padStart(2, '0'))
    .join('')
    .slice(0, 16)
}

async function recordShadowCase(
  ask: string,
  rca: string,
  accessToken: string,
): Promise<ShadowCaseResult> {
  const fingerprint = await alertFingerprint(ask)
  const subjectRef = `rca-${fingerprint}`
  const deterministicCaseId = `case-incident-response-${subjectRef}`
  const headers = {
    Authorization: `Bearer ${accessToken}`,
    'Content-Type': 'application/json',
  }
  try {
    const opened = await fetch(`${caseCoordinatorBase()}/api/v1/case-coordinator/cases`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        caseClass: 'incident-response',
        subjectRef,
        openedBy: 'case-coordinator',
        dispositionTarget: `alert:${fingerprint}`,
      }),
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    })
    if (opened.status !== 201 && opened.status !== 409) {
      console.error('Shadow case open failed', { status: opened.status })
      return { recorded: false, reason: 'unavailable' }
    }
    const caseId = opened.status === 201
      ? ((await opened.json().catch(() => null)) as { caseId?: string } | null)?.caseId
      : deterministicCaseId
    if (!caseId) return { recorded: false, reason: 'unavailable' }

    const signalUrl = `${caseCoordinatorBase()}/api/v1/case-coordinator/cases/${encodeURIComponent(caseId)}/signals`
    const joined = await fetch(signalUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify({ type: 'join', agentId: 'rca-investigator', role: 'incident-investigator' }),
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    })
    if (!joined.ok) {
      console.error('Shadow case join failed', { status: joined.status })
      return { recorded: false, reason: 'unavailable' }
    }
    const contributed = await fetch(signalUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        type: 'contribute',
        agentId: 'rca-investigator',
        summary: rca,
        evidenceRefs: [`holmes-rca:${fingerprint}`],
        contested: false,
      }),
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    })
    if (!contributed.ok) {
      console.error('Shadow case contribution failed', { status: contributed.status })
      return { recorded: false, reason: 'unavailable' }
    }
    return { recorded: true, caseId }
  } catch {
    console.error('Shadow case recording failed')
    return { recorded: false, reason: 'unavailable' }
  }
}

export async function POST(req: NextRequest) {
  const session = await auth()
  if (!session?.user) {
    return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  }
  if (!hasPermission(session.user.roles ?? [], 'system:view')) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }

  const { ask } = (await req.json()) as { ask?: string }
  if (!ask || typeof ask !== 'string' || ask.trim().length < 5) {
    return NextResponse.json({ error: 'ask is required' }, { status: 400 })
  }

  try {
    const upstream = await fetch(`${holmesBase()}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ask: ask.slice(0, 4000), stream: false }),
      signal: AbortSignal.timeout(300_000),
    })
    if (!upstream.ok) {
      // Never disclose an upstream body to the browser. It can contain cluster detail or
      // provider diagnostics that do not belong in an operator-facing error (ADR-0080 P1).
      console.error('HolmesGPT RCA upstream failed', { status: upstream.status })
      return NextResponse.json({ error: 'upstream_error' }, { status: 502 })
    }
    const raw = await upstream.json().catch(() => null)
    const rca = extractRca(raw)
    const mayRecordCase = hasPermission(session.user.roles ?? [], 'agent:execute')
    const shadowCase: ShadowCaseResult = mayRecordCase && session.user.accessToken
      ? await recordShadowCase(ask, rca, session.user.accessToken)
      : { recorded: false, reason: 'not_authorized' }
    return NextResponse.json({ rca, shadowCase })
  } catch {
    console.error('HolmesGPT RCA request failed')
    return NextResponse.json({ error: 'upstream_error' }, { status: 502 })
  }
}
