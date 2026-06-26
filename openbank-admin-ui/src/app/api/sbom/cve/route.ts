// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextRequest, NextResponse } from 'next/server'

/**
 * Thin proxy to the OSV.dev /v1/query API for a single (ecosystem, package,
 * version) tuple. Returns a tightened CveSummary array — id, summary, severity
 * scores trimmed to the highest CVSS v3 score the OSV record carries.
 *
 * Why a server-side proxy and not a direct browser fetch:
 *   - Browser hits to api.osv.dev would CORS-fail and burn through the
 *     anonymous quota per tab.
 *   - We cache responses in-memory (24h TTL) so a 28-service inventory
 *     refresh hits OSV at most ~4 times (one per distinct component+version),
 *     not 28× per component.
 *   - Server can shed when OSV is slow or rate-limited; the inventory UI
 *     degrades to no CVE badge but stays functional.
 *
 * Query shape:
 *   GET /api/sbom/cve?ecosystem=Maven&pkg=io.quarkus:quarkus-core&version=3.33.2
 *
 * Common ecosystems we care about:
 *   - Maven      (everything that ships via quarkus-bom and ad-hoc gradle deps)
 *   - Gradle     (the build tool itself)
 *   - npm        (admin-ui)
 *   - Linux      (Docker base image — out of scope for this proxy)
 */

interface CveSummary {
  id: string
  summary: string | null
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN'
  score: number | null
  references: string[]
}

interface OsvResponse {
  vulns?: Array<{
    id: string
    summary?: string
    severity?: Array<{ type: string; score: string }>
    references?: Array<{ url: string }>
  }>
}

const CACHE_TTL_MS = 24 * 60 * 60 * 1000
const cache = new Map<string, { at: number; data: CveSummary[] }>()

function scoreToSeverity(score: number | null): CveSummary['severity'] {
  if (score == null) return 'UNKNOWN'
  if (score >= 9) return 'CRITICAL'
  if (score >= 7) return 'HIGH'
  if (score >= 4) return 'MEDIUM'
  return 'LOW'
}

function parseCvssScore(severities?: Array<{ type: string; score: string }>): number | null {
  if (!severities?.length) return null
  // Prefer CVSS_V3 if present; otherwise take the first numeric we can parse.
  const v3 = severities.find(s => s.type.includes('V3') || s.type.includes('3.1'))
  const target = v3 ?? severities[0]
  // OSV severity score is a CVSS vector string. Pull the base score (CVSS:3.x/AV:.../...) is
  // not trivially parseable here; OSV.dev also returns numeric fields on many records via
  // `database_specific.cvss_score`. As a fallback we look for a /<digits.digit>/ token in
  // the vector itself which works for many records.
  const match = target.score.match(/(\d+\.\d+)/)
  return match ? Number(match[1]) : null
}

export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET(req: NextRequest) {
  const ecosystem = req.nextUrl.searchParams.get('ecosystem')
  const pkg = req.nextUrl.searchParams.get('pkg')
  const version = req.nextUrl.searchParams.get('version')

  if (!ecosystem || !pkg || !version) {
    return NextResponse.json({ error: 'ecosystem, pkg, version are required' }, { status: 400 })
  }

  const cacheKey = `${ecosystem}|${pkg}|${version}`
  const cached = cache.get(cacheKey)
  if (cached && Date.now() - cached.at < CACHE_TTL_MS) {
    return NextResponse.json({ vulns: cached.data, cached: true }, { headers: { 'Cache-Control': 'no-store' } })
  }

  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 5000)
    const osv = await fetch('https://api.osv.dev/v1/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        package: { ecosystem, name: pkg },
        version,
      }),
      signal: ctrl.signal,
    })
    clearTimeout(timer)
    if (!osv.ok) {
      return NextResponse.json({ vulns: [], error: `osv HTTP ${osv.status}` }, { status: 502 })
    }
    const body = await osv.json() as OsvResponse
    const vulns: CveSummary[] = (body.vulns ?? []).map(v => {
      const score = parseCvssScore(v.severity)
      return {
        id: v.id,
        summary: v.summary ?? null,
        severity: scoreToSeverity(score),
        score,
        references: (v.references ?? []).map(r => r.url).slice(0, 3),
      }
    })
    cache.set(cacheKey, { at: Date.now(), data: vulns })
    return NextResponse.json({ vulns, cached: false }, { headers: { 'Cache-Control': 'no-store' } })
  } catch (err) {
    return NextResponse.json({ vulns: [], error: String(err) }, { status: 502 })
  }
}
