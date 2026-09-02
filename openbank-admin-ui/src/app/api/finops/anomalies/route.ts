// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import type { FinOpsAnomaly } from '../ai-costs/route'
import { requireApiPermission } from '@/lib/auth/api-permission'
export type { FinOpsAnomaly }
export const dynamic = 'force-dynamic'

const ALERTMANAGER_TIMEOUT_MS = 5_000

// Returns active FinOps anomalies from Alertmanager (ADR-0112 D1–D5 detectors).
// When Alertmanager is unreachable, returns empty list (non-blocking).
export async function GET() {
  const access = await requireApiPermission('system:view')
  if (!access.ok) {
    return NextResponse.json({ error: access.error }, { status: access.status })
  }

  const alertmanagerUrl = process.env.ALERTMANAGER_URL ?? 'http://alertmanager-operated.observability:9093'

  try {
    const res = await fetch(`${alertmanagerUrl}/api/v2/alerts?filter=route%3Dfinops-agent`, {
      cache: 'no-store',
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(ALERTMANAGER_TIMEOUT_MS),
    })
    if (!res.ok) return NextResponse.json({ anomalies: [], available: false })

    const raw = await res.json() as Array<{
      labels: Record<string, string>
      annotations: Record<string, string>
      startsAt: string
      status: { state: string }
    }>

    const anomalies: FinOpsAnomaly[] = raw.map((a, i) => ({
      id: `alert-${i}-${a.labels.alertname}`,
      detectedAt: a.startsAt,
      detector: a.labels.detector ?? 'D?',
      severity: (a.labels.severity ?? 'warning') as 'warning' | 'critical',
      title: a.annotations.summary ?? a.labels.alertname,
      rootCause: null,  // populated by finops-agent after LLM diagnosis
      proposalPrUrl: null,
      status: 'open',
      estimatedMonthlySavingUsd: null,
    }))

    return NextResponse.json({ anomalies, available: true })
  } catch {
    return NextResponse.json({ anomalies: [], available: false })
  }
}
