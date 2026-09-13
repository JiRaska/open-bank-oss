// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Lípa console BFF (ADR-0282). Reads the loyalty-service catalogues and the outstanding
// obligation through the ADR-0056 relay.
//
// House style, mirroring /api/incentives: this route ALWAYS 200s with a typed body carrying a
// `state`, so the page can distinguish "the service is not deployed here" from "the service
// answered and there is nothing" — two facts an operator needs kept apart. The MCP endpoint
// incident (#3371) is the reason: admin-ui mapped every error to "not deployed", so a healthy
// service with one unregistered route rendered as an absent service and nobody looked further.

import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { requireApiPermission } from '@/lib/auth/api-permission'
import { serverSvcUrl } from '@/lib/services/bff'

export const dynamic = 'force-dynamic'

const SERVICE = 'loyalty-service'
const NAMESPACE = 'loyalty'
const PORT = 8157
const TIMEOUT_MS = 4000

export type LoyaltyState = 'ok' | 'not_deployed' | 'unreachable' | 'unauthorized'

export interface LoyaltyBenefit {
  id: string
  engine: string
  priceLeaves: number
  validityDays: number
  description: string
}

export interface LoyaltyEarnSource {
  id: string
  leaves: number
  validityDays: number
}

export interface LoyaltyProvisioning {
  at: string
  outstandingLeaves: number
  annualCapPerParty: number
  ruleVersion: string
}

export interface LoyaltyCatalogueResponse {
  state: LoyaltyState
  benefits: LoyaltyBenefit[]
  earnSources: LoyaltyEarnSource[]
  provisioning: LoyaltyProvisioning | null
}

function stateFor(status: number): LoyaltyState {
  if (status === 401 || status === 403) return 'unauthorized'
  // 404 means the route is not registered here — either no service, or a service whose route did
  // not register. Both are "we cannot read it"; only 405 would prove the service is present and
  // the method wrong, which is why this does not claim more than the status supports.
  if (status === 404) return 'not_deployed'
  return 'unreachable'
}

async function readJson<T>(token: string, path: string): Promise<{ state: LoyaltyState; body: T | null }> {
  try {
    const response = await fetch(serverSvcUrl(SERVICE, NAMESPACE, PORT, path), {
      headers: { authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(TIMEOUT_MS),
      cache: 'no-store',
    })
    if (!response.ok) return { state: stateFor(response.status), body: null }
    return { state: 'ok', body: (await response.json()) as T }
  } catch {
    return { state: 'unreachable', body: null }
  }
}

export async function GET() {
  const permitted = await requireApiPermission('loyalty:view')
  if (!permitted.ok) {
    return NextResponse.json({ error: permitted.error }, { status: permitted.status })
  }
  const session = await auth()
  const token = session?.user?.accessToken
  if (!token) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })

  const [benefits, earnSources, provisioning] = await Promise.all([
    readJson<LoyaltyBenefit[]>(token, '/api/v1/loyalty/benefits'),
    readJson<LoyaltyEarnSource[]>(token, '/api/v1/loyalty/earn-sources'),
    readJson<LoyaltyProvisioning>(token, '/api/v1/loyalty/provisioning'),
  ])

  // The worst state wins, so a page never renders two catalogues and a silently missing third as
  // though everything had answered.
  const state: LoyaltyState = [benefits.state, earnSources.state, provisioning.state]
    .find(s => s !== 'ok') ?? 'ok'

  const payload: LoyaltyCatalogueResponse = {
    state,
    benefits: benefits.body ?? [],
    earnSources: earnSources.body ?? [],
    provisioning: provisioning.body,
  }
  return NextResponse.json(payload)
}
