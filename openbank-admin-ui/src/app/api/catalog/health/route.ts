// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'

const SERVICES = [
  { id: 'account',         port: 8100, container: 'openbank-account-service' },
  { id: 'ledger',          port: 8101, container: 'openbank-ledger-service' },
  { id: 'transaction',     port: 8102, container: 'openbank-transaction-service' },
  { id: 'balance',         port: 8103, container: 'openbank-balance-service' },
  { id: 'catalog',         port: 8104, container: 'openbank-product-catalog' },
  { id: 'pid',             port: 8105, container: 'openbank-pid-service' },
  { id: 'consent',         port: 8106, container: 'openbank-consent-service' },
  { id: 'psd2',            port: 8107, container: 'openbank-psd2-service' },
  { id: 'tpp',             port: 8108, container: 'openbank-tpp-registry-service' },
  { id: 'agent',           port: 8109, container: 'openbank-agent-service' },
  { id: 'sca',             port: 8110, container: 'openbank-sca-service' },
  { id: 'party',           port: 8111, container: 'openbank-party-service' },
  { id: 'notification',    port: 8112, container: 'openbank-notification-service' },
  { id: 'audit',           port: 8113, container: 'openbank-audit-service' },
  { id: 'kyc',             port: 8114, container: 'openbank-kyc-service' },
  { id: 'sepa',            port: 8115, container: 'openbank-sepa-payment' },
  { id: 'domestic',        port: 8116, container: 'openbank-domestic-payment' },
  { id: 'aml',             port: 8117, container: 'openbank-aml-service' },
  { id: 'card-issuance',   port: 8118, container: 'openbank-card-issuance-service' },
  { id: 'fx',              port: 8119, container: 'openbank-fx-service' },
  { id: 'security-scanner',port: 8120, container: 'openbank-security-scanner' },
  { id: 'standing-order',  port: 8121, container: 'openbank-standing-order-service' },
  { id: 'swift',           port: 8122, container: 'openbank-swift-service' },
  { id: 'sanctions',       port: 8123, container: 'openbank-sanctions-service' },
  { id: 'clearing',        port: 8124, container: 'openbank-clearing-service' },
  { id: 'interest',        port: 8125, container: 'openbank-interest-service' },
  { id: 'dispute',         port: 8135, container: 'openbank-dispute-service' },
  { id: 'sepa-instant',    port: 8127, container: 'openbank-sepa-instant-service' },
]

const HEALTHY_CODES = new Set([200, 201, 204, 400, 401, 403, 404, 405])

export const dynamic = 'force-dynamic'
export const revalidate = 0

async function probeSvc(svc: typeof SERVICES[0]) {
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
  const results = await Promise.all(SERVICES.map(probeSvc))
  const map: Record<string, typeof results[0]> = {}
  for (const r of results) map[r.id] = r
  return NextResponse.json(map, { headers: { 'Cache-Control': 'no-store' } })
}
