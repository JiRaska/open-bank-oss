// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { loadAdrIndex, type AdrStatus } from '@/lib/governance/docs'

export const dynamic = 'force-dynamic'

// Serves the customer-app dossier artefact (ADR-0074). The build bakes
// app-status.json via scripts/generate-app-status.mjs, which DERIVES facts from
// the customer app's AppConfig.kt + version.txt and joins them with the
// curatorial app-status.yaml. This route is a READ-ONLY consumer (rule #7).
//
// One thing it does compute at view time and never copies: ADR status. Each
// capability declares the ADRs that govern it; we resolve their CURRENT
// status/title against the live ADR index (the same source /docs/adr uses), so
// the dossier can never show a stale ADR status. A capability that names no ADR
// is flagged decisionMissing — a gap operating without a recorded decision.

interface BiText {
  cs: string
  en: string
}

interface RawCapability {
  id: string
  title: BiText
  lens: string[]
  status: 'live' | 'partial' | 'planned'
  adr?: string[]
  gap: BiText
  decisionMissing?: boolean
}

interface ResolvedAdr {
  id: string
  slug: string | null
  title: string | null
  status: AdrStatus
}

interface AppStatus {
  schema: string
  app: { name: string; displayName?: BiText; owner?: string; repo?: string }
  asOf: string | null
  derived: Record<string, unknown>
  capabilities: RawCapability[]
  summary?: unknown
  gaps?: string[]
  available?: boolean
}

function appStatusFile(): string {
  return process.env.OPENBANK_APP_STATUS ?? path.resolve(process.cwd(), 'app-status.json')
}

const UNAVAILABLE: AppStatus = {
  schema: 'openbank.appstatus/v1',
  app: { name: 'openbank-app' },
  asOf: null,
  derived: { sourceAvailable: false },
  capabilities: [],
  available: false,
}

export async function GET() {
  let parsed: AppStatus
  try {
    const raw = await fs.readFile(appStatusFile(), 'utf-8')
    parsed = JSON.parse(raw) as AppStatus
    if (!Array.isArray(parsed?.capabilities)) {
      return NextResponse.json(UNAVAILABLE, { headers: { 'Cache-Control': 'no-store' } })
    }
  } catch {
    return NextResponse.json(UNAVAILABLE, { headers: { 'Cache-Control': 'no-store' } })
  }

  // Resolve ADR ids → current status/title from the live index (never copied).
  const adrs = await loadAdrIndex()
  const byNumber = new Map<number, { slug: string; title: string; status: AdrStatus }>()
  for (const a of adrs) byNumber.set(a.number, { slug: a.slug, title: a.title, status: a.status })

  const resolveAdr = (id: string): ResolvedAdr => {
    const num = parseInt((id.match(/(\d+)/)?.[1] ?? ''), 10)
    const meta = Number.isFinite(num) ? byNumber.get(num) : undefined
    return meta
      ? { id, slug: meta.slug, title: meta.title, status: meta.status }
      : { id, slug: null, title: null, status: 'Unknown' }
  }

  const VALID_STATUS = new Set(['live', 'partial', 'planned'])
  const capabilities = parsed.capabilities
    // Honest by construction: drop malformed entries rather than crash the page.
    .filter((c) => c && Array.isArray(c.lens) && VALID_STATUS.has(c.status))
    .map((c) => {
      const resolvedAdrs = (c.adr ?? []).map(resolveAdr)
      return {
        ...c,
        resolvedAdrs,
        // decision-missing is derived honestly: a gap with no governing ADR at all.
        decisionMissing: c.decisionMissing === true || resolvedAdrs.length === 0,
      }
    })

  // Recompute the summary from the enriched capabilities so it can never
  // contradict the per-capability flags (e.g. an ADR that failed to resolve).
  const byStatus = capabilities.reduce<Record<string, number>>((acc, c) => {
    acc[c.status] = (acc[c.status] || 0) + 1
    return acc
  }, {})
  const summary = {
    total: capabilities.length,
    byStatus,
    decisionMissing: capabilities.filter((c) => c.decisionMissing).map((c) => c.id),
  }

  return NextResponse.json(
    { ...parsed, capabilities, summary, available: true },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
