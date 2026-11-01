// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { inCluster, resolveInClusterService } from '@/lib/discovery'
import { INCIDENT_REGISTER_LIMIT, parseIncidentList, type IctIncident } from '@/lib/security/incidentEvidence'

export const dynamic = 'force-dynamic'
export const revalidate = 0

/** Read-only BFF for the durable DORA ICT incident register (ADR-0146). */
export async function GET(request: Request) {
  const token = (await auth())?.user?.accessToken
  if (!token) return NextResponse.json({ available: false, reason: 'unauthorized' }, { status: 200 })
  const url = new URL(request.url)
  const filters = new URLSearchParams(url.searchParams)
  filters.delete('limit')
  filters.delete('offset')
  let base: string | null
  if (inCluster()) {
    const discovery = await resolveInClusterService('security-scanner-service')
    if (discovery.state === 'found') base = discovery.baseUrl
    else if (discovery.state === 'not_found') {
      return NextResponse.json({ available: false, reason: 'not_deployed' }, { status: 200 })
    } else {
      return NextResponse.json({ available: false, reason: 'discovery_unavailable' }, { status: 200 })
    }
  } else {
    base = process.env.SECURITY_SCANNER_URL ?? null
  }
  if (!base) return NextResponse.json({ available: false, reason: 'not_deployed' }, { status: 200 })
  try {
    const incidents: IctIncident[] = []
    const pageSize = 200
    for (let offset = 0; ; offset += pageSize) {
      const query = new URLSearchParams(filters)
      query.set('limit', String(pageSize))
      query.set('offset', String(offset))
      const response = await fetch(`${base}/api/v1/ict-incidents?${query.toString()}`, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
        cache: 'no-store',
        signal: AbortSignal.timeout(10_000),
      })
      if (!response.ok) return NextResponse.json({ available: false, reason: response.status === 401 || response.status === 403 ? 'unauthorized' : 'error' }, { status: 200 })
      const page = parseIncidentList(await response.json())
      if (!page) return NextResponse.json({ available: false, reason: 'invalid_response' }, { status: 200 })
      incidents.push(...page)
      if (incidents.length > INCIDENT_REGISTER_LIMIT) return NextResponse.json({ available: false, reason: 'result_limit' }, { status: 200 })
      if (page.length < pageSize) break
    }
    const verified = parseIncidentList(incidents)
    if (!verified) return NextResponse.json({ available: false, reason: 'invalid_response' }, { status: 200 })
    return NextResponse.json({ available: true, incidents: verified }, { status: 200 })
  } catch {
    return NextResponse.json({ available: false, reason: 'unreachable' }, { status: 200 })
  }
}
