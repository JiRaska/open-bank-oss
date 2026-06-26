// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextRequest, NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

// BFF proxy to HolmesGPT /api/chat (ADR-0088 Davis-lite, ADR-0031 D9 read-only
// oversight agent). The admin-UI calls this endpoint with a plain-text alert
// description; this route forwards it to the in-cluster HolmesGPT service and
// returns the RCA text. No auth required on this side — the admin-UI is already
// behind AuthGuard (system:view); HolmesGPT is in-cluster only (no ingress).
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
      const err = await upstream.text().catch(() => upstream.statusText)
      return NextResponse.json({ error: `HolmesGPT error: ${err}` }, { status: upstream.status })
    }
    const raw = await upstream.json().catch(() => null)
    return NextResponse.json({ rca: extractRca(raw) })
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    return NextResponse.json({ error: `investigation failed: ${msg}` }, { status: 502 })
  }
}
