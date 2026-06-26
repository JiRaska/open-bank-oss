// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'fs'
import path from 'path'

export const dynamic = 'force-dynamic'

interface EksVersionEntry {
  eks_release: string
  end_of_standard_support: string
  end_of_extended_support: string
}

interface EksLifecycleJson {
  _meta: { pricing_note: string; last_refreshed: string }
  versions: Record<string, EksVersionEntry>
}

const STANDARD_RATE = 0.10  // $/cluster-hr
const EXTENDED_RATE = 0.60  // $/cluster-hr (6× penalty per ADR-0054)
const HOURS_PER_MONTH = 730
const HOURS_PER_YEAR = 8760
const MIN_RUNWAY_DAYS = 180 // ADR-0054: ≥6 months standard support required

// Embedded fallback — matches eks-version-lifecycle.json at 2026-06-01.
// Update when AWS publishes new minor versions.
const EMBEDDED_LIFECYCLE: EksLifecycleJson = {
  _meta: { pricing_note: 'standard: $0.10/hr; extended: $0.60/hr (6× penalty)', last_refreshed: '2026-06-01' },
  versions: {
    '1.30': { eks_release: '2024-05-23', end_of_standard_support: '2025-07-23', end_of_extended_support: '2026-07-23' },
    '1.31': { eks_release: '2024-09-26', end_of_standard_support: '2025-11-26', end_of_extended_support: '2026-11-26' },
    '1.32': { eks_release: '2025-01-23', end_of_standard_support: '2026-03-23', end_of_extended_support: '2027-03-23' },
    '1.33': { eks_release: '2025-05-29', end_of_standard_support: '2026-07-29', end_of_extended_support: '2027-07-29' },
    '1.34': { eks_release: '2025-10-02', end_of_standard_support: '2026-12-02', end_of_extended_support: '2027-12-02' },
    '1.35': { eks_release: '2026-01-27', end_of_standard_support: '2027-03-27', end_of_extended_support: '2028-03-27' },
  },
}

function lifecycleFilePath(): string {
  if (process.env.OPENBANK_FINOPS_LIFECYCLE) return process.env.OPENBANK_FINOPS_LIFECYCLE
  return path.resolve(process.cwd(), '..', 'openbank-infra', 'aws', 'finops', 'eks-version-lifecycle.json')
}

function daysBetween(from: Date, to: Date): number {
  return Math.round((to.getTime() - from.getTime()) / 86_400_000)
}

function runwayStatus(days: number): 'ok' | 'warn' | 'critical' {
  if (days > MIN_RUNWAY_DAYS) return 'ok'
  if (days > 90) return 'warn'
  return 'critical'
}

export async function GET() {
  let lifecycle = EMBEDDED_LIFECYCLE
  let dataSource: 'file' | 'embedded' = 'embedded'

  try {
    const raw = await fs.readFile(lifecycleFilePath(), 'utf-8')
    const parsed = JSON.parse(raw) as EksLifecycleJson
    if (parsed?.versions) { lifecycle = parsed; dataSource = 'file' }
  } catch { /* use embedded */ }

  const currentVersion = process.env.KUBERNETES_VERSION ?? '1.35'
  const now = new Date()

  const versions = Object.entries(lifecycle.versions)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([version, entry]) => {
      const release = new Date(entry.eks_release)
      const stdEnd = new Date(entry.end_of_standard_support)
      const extEnd = new Date(entry.end_of_extended_support)
      const daysToStandardEnd = daysBetween(now, stdEnd)
      const daysToExtendedEnd = daysBetween(now, extEnd)

      let tier: 'upcoming' | 'standard' | 'extended' | 'end_of_life'
      if (now < release) tier = 'upcoming'
      else if (daysToStandardEnd > 0) tier = 'standard'
      else if (daysToExtendedEnd > 0) tier = 'extended'
      else tier = 'end_of_life'

      return {
        version,
        eksRelease: entry.eks_release,
        standardSupportEnds: entry.end_of_standard_support,
        extendedSupportEnds: entry.end_of_extended_support,
        daysToStandardEnd,
        daysToExtendedEnd,
        tier,
        runwayStatus: tier === 'standard' ? runwayStatus(daysToStandardEnd) : (tier === 'extended' ? 'critical' : 'ok'),
        isCurrent: version === currentVersion,
      }
    })

  const current = versions.find(v => v.isCurrent)
  const currentTier = current?.tier ?? 'standard'
  const hourlyRate = currentTier === 'extended' ? EXTENDED_RATE : STANDARD_RATE
  const hourlyDelta = EXTENDED_RATE - STANDARD_RATE
  const onStandard = currentTier === 'standard'

  // Platform components actually running in the sandbox (ADR-0010 GitOps stack).
  // These are NOT AWS-managed services — the data plane is self-hosted in-cluster
  // via operators (CloudNativePG, Strimzi), so we report the management model
  // honestly. A formal support-lifecycle countdown only exists where upstream
  // publishes one (EKS, PostgreSQL major). Self-hosted operators roll versions
  // continuously, so `standardEnd`/`daysRemaining` are null (rendered "rolling")
  // rather than a fabricated date. Versions overridable via env for other envs.
  const components = [
    {
      name: 'Amazon EKS', kind: 'kubernetes',
      version: currentVersion, tier: currentTier,
      managedBy: 'AWS-managed control plane',
      standardEnd: current?.standardSupportEnds ?? null,
      daysRemaining: current?.daysToStandardEnd ?? null,
    },
    {
      name: 'PostgreSQL', kind: 'database',
      version: process.env.POSTGRES_VERSION ?? '16.4',
      tier: 'supported',
      managedBy: 'CloudNativePG (in-cluster operator)',
      // PostgreSQL 16 community EOL (postgresql.org/support/versioning)
      standardEnd: '2028-11-09',
      daysRemaining: daysBetween(now, new Date('2028-11-09')),
    },
    {
      name: 'Apache Kafka', kind: 'messaging',
      version: process.env.KAFKA_VERSION ?? '4.2.0',
      tier: 'rolling',
      managedBy: 'Strimzi 1.0.0 (in-cluster operator)',
      standardEnd: null,
      daysRemaining: null,
    },
    {
      name: 'Apicurio Registry', kind: 'messaging',
      version: process.env.APICURIO_VERSION ?? '2.6.2',
      tier: 'rolling',
      managedBy: 'Schema registry (in-cluster)',
      standardEnd: null,
      daysRemaining: null,
    },
    {
      name: 'Redis', kind: 'cache',
      version: process.env.REDIS_VERSION ?? '7.4.9',
      tier: 'rolling',
      managedBy: 'Self-hosted (in-cluster)',
      standardEnd: null,
      daysRemaining: null,
    },
  ]

  return NextResponse.json({
    currentVersion,
    currentTier,
    daysToStandardEnd: current?.daysToStandardEnd ?? 0,
    runwayStatus: current ? runwayStatus(current.daysToStandardEnd) : 'ok',
    standardSupportEnds: current?.standardSupportEnds ?? '',
    extendedSupportEnds: current?.extendedSupportEnds ?? '',
    hourlyRate,
    monthlyEstimate: Math.round(hourlyRate * HOURS_PER_MONTH),
    annualEstimate: Math.round(hourlyRate * HOURS_PER_YEAR),
    monthlySavingsVsExtended: onStandard ? Math.round(hourlyDelta * HOURS_PER_MONTH) : 0,
    annualSavingsVsExtended: onStandard ? Math.round(hourlyDelta * HOURS_PER_YEAR) : 0,
    minRunwayDays: MIN_RUNWAY_DAYS,
    versions,
    components,
    dataSource,
    lastRefreshed: lifecycle._meta.last_refreshed,
    adrRef: 'ADR-0054',
  }, { headers: { 'Cache-Control': 'no-store' } })
}
