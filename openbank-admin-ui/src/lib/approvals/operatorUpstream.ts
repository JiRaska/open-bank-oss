// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import 'server-only'
import { NextResponse } from 'next/server'
import { auth } from '@/auth'
import { hasPermission } from '@/lib/auth/roles'
import { serverSvcUrl } from '@/lib/services/bff'
import { isApprovalId, type OperatorApprovalDomain } from '@/lib/approvals/operator'

const UPSTREAM: Record<OperatorApprovalDomain, { service: string; namespace: string; port: number; path: string }> = {
  sca: { service: 'sca-service', namespace: 'sca', port: 8110, path: '/api/v1/sca/approvals' },
  settlement: { service: 'settlement-service', namespace: 'payments', port: 8138, path: '/api/v1/settlements/approvals' },
}

/**
 * Relays one checker read or decision with the operator's own bearer. The session/role gate here
 * mirrors the services' @RolesAllowed(OPERATOR, ADMIN); the services still enforce OPA and the
 * maker/checker separation. Only `{approve}` is forwarded — nothing from the body can name a
 * maker, checker or target. Upstream error bodies are never relayed.
 */
export async function forwardOperatorApproval(
  domain: OperatorApprovalDomain,
  id: string,
  decision?: boolean,
): Promise<NextResponse> {
  const session = await auth()
  if (!session?.user?.accessToken) return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  if (!hasPermission(session.user.roles ?? [], 'operator-approvals:decide')) {
    return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  }
  if (!isApprovalId(id)) return NextResponse.json({ error: 'invalid_approval_id' }, { status: 400 })
  const upstream = UPSTREAM[domain]
  try {
    const response = await fetch(serverSvcUrl(upstream.service, upstream.namespace, upstream.port, `${upstream.path}/${id}`), {
      method: decision === undefined ? 'GET' : 'PATCH',
      headers: { Authorization: `Bearer ${session.user.accessToken}`, 'Content-Type': 'application/json' },
      body: decision === undefined ? undefined : JSON.stringify({ approve: decision }),
      cache: 'no-store',
      signal: AbortSignal.timeout(8000),
    })
    if (!response.ok) {
      const status = [400, 401, 403, 404, 409].includes(response.status) ? response.status : 502
      return NextResponse.json({ error: 'upstream_error' }, { status })
    }
    return NextResponse.json(await response.json(), { headers: { 'Cache-Control': 'no-store' } })
  } catch {
    return NextResponse.json({ error: 'upstream_unreachable' }, { status: 502 })
  }
}

/** Parses the PATCH body: exactly a boolean `approve`, anything else is a 400 without forwarding. */
export async function readDecision(request: Request): Promise<boolean | null> {
  let body: unknown
  try { body = await request.json() } catch { return null }
  if (!body || typeof body !== 'object' || !('approve' in body)) return null
  const approve = (body as { approve: unknown }).approve
  return typeof approve === 'boolean' ? approve : null
}
