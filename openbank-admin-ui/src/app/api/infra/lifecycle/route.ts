// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Infrastructure lifecycle & vulnerability intelligence (ADR-0079). Joins three honest
// sources into a per-component verdict:
//   1. running version  — live build-info probe (versionSource=probe) or the GitOps image
//      tag baked into infra-lifecycle.json (versionSource=gitops);
//   2. lifecycle         — endoflife.date cycles from the baked snapshot (eol/lts/latest);
//   3. vulnerabilities   — Grype summary from infra-vulns.json (or the ConfigMap, future).
// Every field carries a `source`; gaps are explicit, never faked.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'
import { inCluster } from '@/lib/discovery'
import { currentInfraDigests, verifiedRuntimeScans, type RuntimeSnapshot } from '@/lib/infra-vulns-runtime'

export const dynamic = 'force-dynamic'
export const revalidate = 0

// ---- baked snapshots -------------------------------------------------------

interface EolCycle {
  cycle: string
  releaseDate: string | null
  eol: string | boolean | null
  latest: string | null
  latestReleaseDate: string | null
  lts: string | boolean
  support: string | boolean | null
  link: string | null
}
interface LifecycleComponent {
  id: string
  eolProduct: string | null
  versionSource: 'gitops' | 'probe' | 'unknown'
  gitopsVersion: string | null
  releaseNotes: string | null
  lifecycle: { cycles?: EolCycle[]; error?: string } | null
}
interface LifecycleSnapshot {
  generatedAt?: string
  components?: LifecycleComponent[]
}
interface VulnSummary {
  image?: string
  critical: number
  high: number
  medium: number
  low: number
  total: number
  top?: { id: string; severity: string; fixedIn?: string | null }[]
}
interface VulnSnapshot {
  scannedAt?: string | null
  images?: Record<string, VulnSummary>
}

async function readJson<T>(envVar: string, fallbackFile: string): Promise<T | null> {
  const file = process.env[envVar] ?? path.resolve(process.cwd(), fallbackFile)
  try {
    return JSON.parse(await fs.readFile(file, 'utf-8')) as T
  } catch {
    return null
  }
}

// ---- live version probes (in-cluster only) ---------------------------------

// Mirrors the Service DNS used by /api/infra/status. Each returns the running version
// string or null (honest unknown). Build-info endpoints, short timeouts, fail soft.
const VERSION_PROBES: Record<string, { url: string; pick: (j: unknown) => string | null }> = {
  openbao: {
    // OpenBao replaced Vault (runbook 0005). /sys/health is API-compatible and
    // self-reports a clean version (e.g. "2.5.4"); Service is openbao.vault.svc.
    url: 'http://openbao.vault.svc:8200/v1/sys/health',
    pick: j => (j as { version?: string })?.version ?? null,
  },
  grafana: {
    url: 'http://kube-prometheus-stack-grafana.observability.svc:80/api/health',
    pick: j => (j as { version?: string })?.version ?? null,
  },
  prometheus: {
    url: 'http://kube-prometheus-stack-prometheus.observability.svc:9090/api/v1/status/buildinfo',
    pick: j => (j as { data?: { version?: string } })?.data?.version ?? null,
  },
  loki: {
    url: 'http://loki.observability.svc:3100/loki/api/v1/status/buildinfo',
    // grafana/loki images self-report the release BRANCH (e.g. "release-3.3.x-23b5fc2"),
    // not a clean tag. Extract the x.y(.z) so the card shows "3.3" (EoL-matchable), not
    // the branch-build string. Other components' build-info already returns a clean version.
    pick: j => {
      const raw = (j as { version?: string })?.version
      return raw?.match(/\d+\.\d+(?:\.\d+)?/)?.[0] ?? raw ?? null
    },
  },
  tempo: {
    url: 'http://tempo.observability.svc:3200/api/status/buildinfo',
    pick: j => (j as { version?: string })?.version ?? null,
  },
}

async function probeVersion(id: string): Promise<string | null> {
  const probe = VERSION_PROBES[id]
  if (!probe) return null
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 3500)
    const res = await fetch(probe.url, { signal: ctrl.signal, cache: 'no-store' })
    clearTimeout(timer)
    if (!res.ok) return null
    const v = probe.pick(await res.json())
    return v ? String(v).replace(/^v/, '').trim() : null
  } catch {
    return null
  }
}

// ---- semver helpers --------------------------------------------------------

function parts(v: string): number[] {
  return v.split(/[.+-]/).map(n => parseInt(n, 10)).filter(n => !Number.isNaN(n))
}
function cmp(a: string, b: string): number {
  const pa = parts(a), pb = parts(b)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0)
    if (d !== 0) return d > 0 ? 1 : -1
  }
  return 0
}
// The endoflife cycle whose label is the longest prefix of the running version
// (cycles are "16" for Postgres, "1.17" for Vault, "11" for Grafana, …).
function matchCycle(version: string, cycles: EolCycle[]): EolCycle | null {
  let best: EolCycle | null = null
  for (const c of cycles) {
    if (version === c.cycle || version.startsWith(c.cycle + '.')) {
      if (!best || c.cycle.length > best.cycle.length) best = c
    }
  }
  return best
}
function daysUntil(iso: string): number {
  return Math.round((new Date(iso).getTime() - Date.now()) / 86_400_000)
}

type Urgency = 'current' | 'patch-available' | 'major-available' | 'vulnerable' | 'eol-soon' | 'eol' | 'unknown'

export async function GET() {
  const snap = await readJson<LifecycleSnapshot>('OPENBANK_INFRA_LIFECYCLE', 'infra-lifecycle.json')
  const vulns = await readJson<VulnSnapshot>('OPENBANK_INFRA_VULNS', 'infra-vulns.json')
  const runtimeVulns = await readJson<RuntimeSnapshot>('OPENBANK_INFRA_VULNS_V2', 'infra-vulns-v2.json')
  if (!snap?.components?.length) {
    return NextResponse.json({ error: 'lifecycle snapshot unavailable' }, { status: 503 })
  }

  const probeOn = inCluster()
  // A scanner-produced identity cannot prove it is still the running identity.
  // Denied or unavailable Kubernetes reads return null for each component.
  const liveImages = probeOn ? await currentInfraDigests() : {}
  const components = await Promise.all(snap.components.map(async c => {
    // 1. running version
    let running: string | null = null
    let versionSource: string
    if (c.versionSource === 'gitops') {
      running = c.gitopsVersion
      versionSource = 'gitops-image-tag'
    } else if (c.versionSource === 'probe' && probeOn) {
      running = await probeVersion(c.id)
      versionSource = running ? 'build-info-probe' : 'unavailable'
    } else {
      versionSource = 'unknown'
    }

    // 2. lifecycle from endoflife cycles
    const cycles = c.lifecycle?.cycles ?? []
    const newest = cycles[0] ?? null
    const matched = running && cycles.length ? matchCycle(running, cycles) : null

    let eolDate: string | null = null
    let eolPassed = false
    let eolDaysLeft: number | null = null
    if (matched) {
      if (matched.eol === true) eolPassed = true
      else if (typeof matched.eol === 'string') {
        eolDate = matched.eol
        eolDaysLeft = daysUntil(matched.eol)
        eolPassed = eolDaysLeft < 0
      }
    }
    const isLts = !!matched && matched.lts !== false && matched.lts != null
    const support = matched?.support ?? null

    const latestInCycle = matched?.latest ?? null
    const patchAvailable = !!(running && latestInCycle && cmp(running, latestInCycle) < 0)
    const majorAvailable = !!(matched && newest && newest.cycle !== matched.cycle &&
      newest.latest && running && cmp(newest.latest, running) > 0)

    // recommended upgrade target: safe patch first, else the newest major
    const target = patchAvailable ? latestInCycle : (majorAvailable ? newest?.latest ?? null : null)
    const releaseNotesUrl = c.releaseNotes && target
      ? c.releaseNotes.replace('{version}', target)
      : c.releaseNotes ?? null

    // 3. vulnerabilities
    // A snapshot with no scannedAt is the unpopulated placeholder: whatever it carries is not a
    // scan result, so the component must read "not yet scanned", never "0 vulnerabilities".
    const scans = verifiedRuntimeScans(runtimeVulns, c.id, liveImages[c.id] ?? null)
    const old = vulns?.scannedAt ? (vulns.images?.[c.id] ?? null) : null
    const cve = scans
      ? { scanned: true, status: 'verified', critical: scans.reduce((n, s) => n + s.critical, 0),
          high: scans.reduce((n, s) => n + s.high, 0), medium: scans.reduce((n, s) => n + s.medium, 0),
          low: scans.reduce((n, s) => n + s.low, 0), total: scans.reduce((n, s) => n + s.total, 0),
          top: scans.flatMap(s => s.top ?? []).slice(0, 12) }
      : { scanned: false, status: old ? 'legacy-unverified' : 'unavailable',
          critical: 0, high: 0, medium: 0, low: 0, total: 0,
          top: [] as VulnSummary['top'],
          legacy: old ? { critical: old.critical, high: old.high, medium: old.medium,
            low: old.low, total: old.total } : undefined }
    const highCrit = cve.critical + cve.high

    // urgency (worst wins)
    let urgency: Urgency = 'unknown'
    if (running && (matched || !c.eolProduct)) {
      if (eolPassed) urgency = 'eol'
      else if (eolDaysLeft !== null && eolDaysLeft <= 90) urgency = 'eol-soon'
      else if (highCrit > 0) urgency = 'vulnerable'
      else if (majorAvailable) urgency = 'major-available'
      else if (patchAvailable) urgency = 'patch-available'
      else urgency = cve.scanned ? 'current' : 'unknown'
    }

    return {
      id: c.id,
      running: { version: running, source: versionSource },
      lifecycle: c.eolProduct
        ? {
            available: cycles.length > 0,
            product: c.eolProduct,
            cycle: matched?.cycle ?? null,
            isLts,
            ltsRaw: matched?.lts ?? null,
            eol: eolDate,
            eolPassed,
            eolDaysLeft,
            support,
            releaseDate: matched?.releaseDate ?? null,
            latestInCycle,
            newestVersion: newest?.latest ?? null,
            newestCycle: newest?.cycle ?? null,
          }
        : { available: false, product: null, reason: 'no public lifecycle feed' },
      upgrade: { patchAvailable, majorAvailable, target, releaseNotesUrl },
      cve,
      urgency,
    }
  }))

  return NextResponse.json(
    {
      schema: 'openbank.infra-lifecycle.view/v1',
      lifecycleGeneratedAt: snap.generatedAt ?? null,
      vulnScannedAt: Object.keys(liveImages).some(id => verifiedRuntimeScans(runtimeVulns, id, liveImages[id]))
        ? runtimeVulns?.scannedAt ?? null : null,
      probed: probeOn,
      components,
    },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
