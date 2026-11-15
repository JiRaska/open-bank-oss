// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Compliance trace (ADR-0379): obligation -> control -> evidence, read-only. The same JSON is the
// auditor export (`?download=1` sets a Content-Disposition with the commit it describes).

import { NextResponse } from 'next/server'
import { loadComplianceTrace } from '@/lib/compliance/trace-server'

export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET(request: Request) {
  const trace = await loadComplianceTrace()
  const headers: Record<string, string> = { 'Cache-Control': 'no-store, max-age=0' }
  if (new URL(request.url).searchParams.get('download') === '1') {
    const commit = trace.status === 'available' && trace.asOf.commit ? trace.asOf.commit.slice(0, 12) : 'unknown-commit'
    headers['Content-Disposition'] = `attachment; filename="openbank-compliance-trace-${commit}.json"`
  }
  return NextResponse.json(trace, { headers })
}
