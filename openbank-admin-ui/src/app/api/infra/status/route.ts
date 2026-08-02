// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.


// Thin handler. The probe definitions and logic live in `@/lib/infra/probes` so this file exports
// only what a Next route file is allowed to (#3235 — webpack rejects anything else, and webpack is
// the only bundler here that emits client source maps).

import { NextResponse } from 'next/server'
import { INFRA, probeInfra, type InfraStatusResult } from '@/lib/infra/probes'

export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET() {
  const results = await Promise.all(INFRA.map(probeInfra))
  const map: Record<string, InfraStatusResult> = {}
  for (const r of results) map[r.id] = r
  return NextResponse.json(map, { headers: { 'Cache-Control': 'no-store' } })
}
