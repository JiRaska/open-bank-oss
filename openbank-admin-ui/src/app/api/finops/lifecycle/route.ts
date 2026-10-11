// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { promises as fs } from 'node:fs'
import path from 'node:path'
import { getEksLifecycle, getPlatformView } from '@/lib/live-platform-versions'
import { versionText } from '@/lib/platform-view'
import { componentVersion, platformVersions } from '@/lib/platform-versions'
import { postgresEol, postgresPins } from '@/lib/postgres-platform-lifecycle'

export const dynamic = 'force-dynamic'

const STANDARD_RATE = 0.10  // $/cluster-hr
const EXTENDED_RATE = 0.60  // $/cluster-hr (6× penalty per ADR-0054)
const HOURS_PER_MONTH = 730
const HOURS_PER_YEAR = 8760
const MIN_RUNWAY_DAYS = 180 // ADR-0054: ≥6 months standard support required

function daysBetween(from: Date, to: Date): number {
  return Math.round((to.getTime() - from.getTime()) / 86_400_000)
}

function runwayStatus(days: number): 'ok' | 'warn' | 'critical' {
  if (days > MIN_RUNWAY_DAYS) return 'ok'
  if (days > 90) return 'warn'
  return 'critical'
}

export async function GET() {
  // EKS support dates: endoflife.date at runtime (24h cache), falling back to the build-time
  // snapshot. The running version is LIVE (Prometheus) with the declared value alongside; if neither
  // is known the route reports unavailable instead of guessing a cluster version.
  const [view, eol] = await Promise.all([getPlatformView(), getEksLifecycle()])
  const k8s = view.items.kubernetes
  const liveMinor = k8s.live?.[0]?.version.split('.').slice(0, 2).join('.') ?? null
  const currentVersion = liveMinor ?? k8s.declared
  if (!eol || !currentVersion) {
    return NextResponse.json({ error: 'platform_versions_unavailable' }, { status: 503, headers: { 'Cache-Control': 'no-store' } })
  }
  const lifecycle = eol.lifecycle
  const dataSource = eol.source === 'snapshot' ? 'snapshot' as const : 'live' as const
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

  // The infra lifecycle collector refreshes this endoflife.date snapshot before image build.
  // If it is missing or lacks the pinned major, report unknown rather than a fabricated EOL.
  let infraLifecycle: unknown = null
  try {
    infraLifecycle = JSON.parse(await fs.readFile(
      process.env.OPENBANK_INFRA_LIFECYCLE ?? path.resolve(process.cwd(), 'infra-lifecycle.json'),
      'utf8',
    ))
  } catch { /* lifecycle state is explicitly unknown below */ }

  // Include every running version and every declared GitOps pin. The live query may be unavailable,
  // while an extra running version may have no matching declared pin. Neither case is hidden.
  const declaredPins = postgresPins(platformVersions?.components.postgres ?? null)
  const livePins = view.items.postgres?.live ?? []
  const postgresVersions = [...new Set([
    ...declaredPins.map(pin => pin.version),
    ...livePins.map(pin => pin.version),
  ])]
  const postgresComponents = postgresVersions.map(version => {
    const declared = declaredPins.find(pin => pin.version === version)?.manifests
    const live = livePins.find(pin => pin.version === version)?.count
    const counts = [
      ...(live === undefined ? [] : [`${live} running ${live === 1 ? 'pod' : 'pods'}`]),
      ...(declared === undefined ? [] : [`${declared} GitOps ${declared === 1 ? 'manifest' : 'manifests'}`]),
    ]
    const standardEnd = postgresEol(version, infraLifecycle)
    const daysRemaining = standardEnd ? daysBetween(now, new Date(`${standardEnd}T00:00:00Z`)) : null
    return {
      name: `PostgreSQL (${counts.join('; ')})`,
      kind: 'database',
      version,
      tier: daysRemaining === null ? 'unknown' : daysRemaining > 0 ? 'supported' : 'end_of_life',
      managedBy: 'CloudNativePG (in-cluster operator)',
      standardEnd,
      daysRemaining,
    }
  })
  if (postgresComponents.length === 0) {
    postgresComponents.push({
      name: 'PostgreSQL', kind: 'database', version: componentVersion('postgres'),
      tier: 'unknown', managedBy: 'CloudNativePG (in-cluster operator)',
      standardEnd: null, daysRemaining: null,
    })
  }

  // GitOps image pins express desired versions, not live pod state. Only EKS and PostgreSQL
  // have a dated lifecycle in these snapshots; unknown dates must remain unknown.
  const components = [
    {
      name: 'Amazon EKS', kind: 'kubernetes',
      version: versionText(k8s), tier: currentTier,
      managedBy: 'AWS-managed control plane',
      standardEnd: current?.standardSupportEnds ?? null,
      daysRemaining: current?.daysToStandardEnd ?? null,
    },
    ...postgresComponents,
    {
      name: 'Apache Kafka', kind: 'messaging',
      version: versionText(view.items.kafka),
      tier: 'rolling',
      managedBy: `Strimzi ${versionText(view.items.strimziOperator)} (in-cluster operator)`,
      standardEnd: null,
      daysRemaining: null,
    },
    {
      name: 'Apicurio Registry', kind: 'messaging',
      version: versionText(view.items.apicurio),
      tier: 'rolling',
      managedBy: 'Schema registry (in-cluster)',
      standardEnd: null,
      daysRemaining: null,
    },
    {
      name: 'Valkey (Redis-compatible)', kind: 'cache',
      version: versionText(view.items.valkey),
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
    platform: view,
    lastRefreshed: lifecycle._meta.last_refreshed,
    adrRef: 'ADR-0054',
  }, { headers: { 'Cache-Control': 'no-store' } })
}
