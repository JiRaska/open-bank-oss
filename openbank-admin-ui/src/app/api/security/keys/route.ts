// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'
import { buildKeysReport } from '@/lib/security/keys/report'
import { measure, prometheusBase } from '@/lib/security/keys/prometheus'

// ── Keys & Certificates: READ-ONLY, metadata only (#16292) ───────────────────
//
// Reads expiry timestamps from operator metrics and joins them with the descriptive
// catalogue. This route never reads a Kubernetes Secret, never calls OpenBao, and never
// sees a private key, a keystore or a certificate body: the only inputs are metric values
// and their labels, projected onto a closed field set in lib/security/keys/prometheus.ts.
//
// Two audiences, one route: everyone with keys:view gets the overview (catalogue text and
// aggregate status per entry); keys:expert additionally gets the per-certificate list.
// The expert list is omitted server-side, not hidden client-side.
//
// ADR-0056 degradation: always 200 with a typed envelope. A metric family that did not
// answer is reported as unreachable and its entries as `unknown`, never as healthy.

export const dynamic = 'force-dynamic'
export const revalidate = 0
export const runtime = 'nodejs'

export async function GET(): Promise<NextResponse> {
  const session = await auth()
  const roles = session?.user?.roles ?? []
  if (!session?.user) return NextResponse.json({ error: 'unauthenticated' }, { status: 401 })
  if (!hasPermission(roles, 'keys:view')) return NextResponse.json({ error: 'forbidden' }, { status: 403 })

  const m = await measure(prometheusBase())
  const report = buildKeysReport(m, Math.floor(Date.now() / 1000), hasPermission(roles, 'keys:expert'))
  return NextResponse.json(report, { status: 200, headers: { 'Cache-Control': 'no-store' } })
}
