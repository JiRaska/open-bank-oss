// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { getRegistry } from '@/lib/services/fleet'

// Probe list DERIVED from the code-generated catalog (see @/lib/services/fleet).
function services(): { id: string; port: number; container: string }[] {
  return getRegistry().map(s => ({ id: s.id, port: s.port, container: s.container }))
}

const HEALTHY_CODES = new Set([200, 201, 204, 400, 401, 403, 404, 405])

export const dynamic = 'force-dynamic'
export const revalidate = 0

async function probeSvc(svc: ReturnType<typeof services>[number]) {
  const host = process.env.SERVICES_HOST === 'container' ? svc.container : (process.env.SERVICES_HOST ?? 'localhost')
  const base = `http://${host}:${svc.port}`

  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 5000)

    const [apiRes, openapiRes, infoRes] = await Promise.allSettled([
      fetch(`${base}/api/v1/info`, { signal: ctrl.signal, cache: 'no-store' }),
      fetch(`${base}/q/openapi?format=json`, { signal: ctrl.signal, cache: 'no-store' }),
      fetch(`${base}/q/health`, { signal: ctrl.signal, cache: 'no-store' }),
    ])
    clearTimeout(timer)

    const up = (apiRes.status === 'fulfilled' && HEALTHY_CODES.has(apiRes.value.status))
      || (infoRes.status === 'fulfilled' && HEALTHY_CODES.has(infoRes.value.status))

    let openapi = null
    let paths: string[] = []
    if (openapiRes.status === 'fulfilled' && openapiRes.value.ok) {
      try {
        openapi = await openapiRes.value.json()
        if (openapi?.paths) paths = Object.keys(openapi.paths)
      } catch { openapi = null }
    }

    let info = null
    if (apiRes.status === 'fulfilled' && apiRes.value.ok) {
      try { info = await apiRes.value.json() } catch { info = null }
    }

    return { id: svc.id, up, paths, openapi, info }
  } catch {
    return { id: svc.id, up: false, paths: [], openapi: null, info: null }
  }
}

export async function GET() {
  const results = await Promise.all(services().map(probeSvc))
  const map: Record<string, typeof results[0]> = {}
  for (const r of results) map[r.id] = r
  return NextResponse.json(map, { headers: { 'Cache-Control': 'no-store' } })
}
