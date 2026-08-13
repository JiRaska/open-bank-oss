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
    return NextResponse.json({ rca: extractRca(raw) })
  } catch {
    console.error('HolmesGPT RCA request failed')
    return NextResponse.json({ error: 'upstream_error' }, { status: 502 })
  }
}
