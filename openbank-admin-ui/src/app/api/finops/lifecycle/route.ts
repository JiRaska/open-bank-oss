// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { componentVersion, platformVersions } from '@/lib/platform-versions'

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
  // Everything EKS-related comes from platform-versions.json (generated from variables.tf and
  // eks-version-lifecycle.json). No embedded table and no default version: a missing snapshot is
  // reported as unavailable, never as a guessed cluster version.
  if (!platformVersions) {
    return NextResponse.json({ error: 'platform_versions_snapshot_missing' }, { status: 503, headers: { 'Cache-Control': 'no-store' } })
  }
  const lifecycle = platformVersions.eksLifecycle
  const dataSource = 'file' as const
  const currentVersion = platformVersions.kubernetesVersion
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
      version: componentVersion('postgres'),
      tier: 'supported',
      managedBy: 'CloudNativePG (in-cluster operator)',
      // No pinned source for a community EOL date: shown as rolling, never a typed date.
      standardEnd: null,
      daysRemaining: null,
    },
    {
      name: 'Apache Kafka', kind: 'messaging',
      version: componentVersion('kafka'),
      tier: 'rolling',
      managedBy: `Strimzi ${componentVersion('strimziOperator')} (in-cluster operator)`,
      standardEnd: null,
      daysRemaining: null,
    },
    {
      name: 'Apicurio Registry', kind: 'messaging',
      version: componentVersion('apicurio'),
      tier: 'rolling',
      managedBy: 'Schema registry (in-cluster)',
      standardEnd: null,
      daysRemaining: null,
    },
    {
      name: 'Valkey (Redis-compatible)', kind: 'cache',
      version: componentVersion('valkey'),
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
