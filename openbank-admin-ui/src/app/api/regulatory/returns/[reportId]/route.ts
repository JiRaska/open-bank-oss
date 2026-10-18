// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Regulatory report generation BFF — the catalogue reports finrep-service does not render
// (SDAT PLAT/VKLA, FAÚ SAR, ECB payment statistics, FATCA, DORA ICT incidents).
//
// GET ?period=YYYY-MM|YYYY → the generated report in the finrep template/cell shape. Generation
// only: nothing is transmitted to a regulator. Error contract matches the /api/svc proxy so the
// page reuses classifyBffFailure(): 401 unauthorized, 404 {error:"Unknown service …"} for an
// undeployed source, 502 {error:"upstream_unreachable"}, else a generic 500/400 — the upstream
// detail stays in the server log.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'
import {
  ReportSourceError, defaultPeriod, generateReport, isGeneratedReportId, periodBounds, periodKindOf,
} from '@/lib/regulatory/generatedReports'
import { createReportSourceClient } from '@/lib/regulatory/upstream'

export const dynamic = 'force-dynamic'
export const revalidate = 0

type RouteContext = { params: Promise<{ reportId: string }> }

function envNumber(name: string): number | undefined {
  const raw = process.env[name]?.trim()
  if (!raw) return undefined
  const n = Number(raw)
  return Number.isInteger(n) && n >= 0 ? n : undefined
}

export async function GET(request: Request, { params }: RouteContext) {
  const session = await auth()
  const accessToken = session?.user?.accessToken
  if (!session?.user || !accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  if (!hasPermission(session.user.roles ?? [], 'regulatory:view')) return NextResponse.json({ error: 'forbidden' }, { status: 403 })

  const { reportId } = await params
  if (!isGeneratedReportId(reportId)) return NextResponse.json({ error: 'unknown_report' }, { status: 404 })
  const kind = periodKindOf(reportId)
  const period = new URL(request.url).searchParams.get('period') ?? defaultPeriod(kind, new Date())
  if (!periodBounds(kind, period)) return NextResponse.json({ error: 'invalid_period' }, { status: 400 })

  try {
    const report = await generateReport(reportId, period, createReportSourceClient(accessToken), {
      atmCount: envNumber('REGULATORY_ATM_COUNT'),
      mlroContact: process.env.REGULATORY_MLRO_CONTACT?.trim() || undefined,
      taxEntityId: process.env.REGULATORY_TAX_ENTITY_ID?.trim() || 'company',
    })
    return NextResponse.json(report, { headers: { 'Cache-Control': 'no-store' } })
  } catch (err) {
    if (err instanceof ReportSourceError) {
      console.error(`regulatory BFF: ${reportId} ${period} failed: ${err.message}`)
      switch (err.kind) {
        case 'not_deployed': return NextResponse.json({ error: `Unknown service: ${err.service}`, service: err.service }, { status: 404 })
        case 'unauthorized': return NextResponse.json({ error: 'unauthorized', service: err.service }, { status: 403 })
        case 'unreachable': return NextResponse.json({ error: 'upstream_unreachable', service: err.service }, { status: 502 })
        case 'result_limit': return NextResponse.json({ error: 'result_limit', service: err.service }, { status: 500 })
        default: return NextResponse.json({ error: 'upstream_error', service: err.service }, { status: 500 })
      }
    }
    console.error(`regulatory BFF: ${reportId} ${period} failed:`, err)
    return NextResponse.json({ error: 'upstream_error' }, { status: 500 })
  }
}
