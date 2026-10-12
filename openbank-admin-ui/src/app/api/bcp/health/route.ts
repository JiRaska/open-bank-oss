// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { getRegistry } from '@/lib/services/fleet'
import { gatusContainer } from '@/lib/services/gatus'

const GATUS_URL = process.env.GATUS_URL ?? 'http://openbank-gatus:8080'

interface GatusEndpoint {
  key: string
  results: { success: boolean }[]
}

export const dynamic = 'force-dynamic'
export const revalidate = 0

export async function GET() {
  const registry = getRegistry()
  if (registry.length === 0) {
    return NextResponse.json(
      { error: 'catalog_unavailable' },
      { status: 503, headers: { 'Cache-Control': 'no-store' } },
    )
  }

  try {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), 5000)
    const res = await fetch(`${GATUS_URL}/api/v1/endpoints/statuses`, {
      signal: ctrl.signal,
      cache: 'no-store',
    })
    clearTimeout(t)

    if (!res.ok) throw new Error(`Gatus returned ${res.status}`)

    const endpoints: GatusEndpoint[] = await res.json()

    const result: Record<string, 'healthy' | 'unhealthy'> = {}
    for (const ep of endpoints) {
      const container = gatusContainer(ep.key, registry)
      if (!container) continue
      const last = ep.results.at(-1)
      const status: 'healthy' | 'unhealthy' = last?.success ? 'healthy' : 'unhealthy'
      if (result[container] === 'unhealthy') continue
      result[container] = status
    }

    return NextResponse.json(result, { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json(
      { error: 'gatus_unavailable' },
      { status: 503, headers: { 'Cache-Control': 'no-store' } }
    )
  }
}
