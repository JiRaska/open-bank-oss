// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest, NextResponse } from 'next/server'
import { inCluster, discoverServices } from '@/lib/discovery'
import { auth } from '@/auth'

// Off-cluster (local dev / docker-compose) fallback only — in-cluster the proxy
// resolves via ADR-0051 discovery instead. The KEY is therefore not free-form:
// it is the caller-supplied `/api/svc/<key>` segment, which in-cluster is looked
// up verbatim against real Kubernetes workload names. A key that does not match a
// deployed Deployment/Service/Rollout can only ever work off-cluster and 404s
// (`Unknown service`) in the sandbox — which is exactly how `sepa-instant-service`
// left the payments SCT-Inst panel stuck on `not_deployed`. Keys are checked
// against openbank-infra/gitops by src/test/service-registry.guard.test.ts.
const SERVICE_MAP: Record<string, { container: string; port: number }> = {
  'account-service':        { container: 'openbank-account-service',        port: 8100 },
  'ledger-service':         { container: 'openbank-ledger-service',         port: 8101 },
  'transaction-service':    { container: 'openbank-transaction-service',    port: 8102 },
  'balance-service':        { container: 'openbank-balance-service',        port: 8103 },
  'product-catalog':        { container: 'openbank-product-catalog',        port: 8104 },
  'pid-service':            { container: 'openbank-pid-service',            port: 8105 },
  'consent-service':        { container: 'openbank-consent-service',        port: 8106 },
  'psd2-service':           { container: 'openbank-psd2-service',           port: 8107 },
  'tpp-registry-service':   { container: 'openbank-tpp-registry-service',   port: 8108 },
  'agent-service':          { container: 'openbank-agent-service',          port: 8109 },
  'sca-service':            { container: 'openbank-sca-service',            port: 8110 },
  'party-service':          { container: 'openbank-party-service',          port: 8111 },
  'notification-service':   { container: 'openbank-notification-service',   port: 8112 },
  'audit-service':          { container: 'openbank-audit-service',          port: 8113 },
  'kyc-service':            { container: 'openbank-kyc-service',            port: 8114 },
  'sepa-payment':           { container: 'openbank-sepa-payment',           port: 8115 },
  'domestic-payment':       { container: 'openbank-domestic-payment',       port: 8116 },
  'aml-service':            { container: 'openbank-aml-service',            port: 8117 },
  'card-issuance-service':  { container: 'openbank-card-issuance-service',  port: 8118 },
  // ADR-0283 phase 3: the Card Center's token and dispute desks read this service. Without the
  // entry the browser cannot reach it at all and the screens can only show mock data.
  'card-processing-service': { container: 'openbank-card-processing-service', port: 8157 },
  'fx-service':             { container: 'openbank-fx-service',             port: 8119 },
  'security-scanner-service': { container: 'openbank-security-scanner',     port: 8120 },
  'standing-order-service': { container: 'openbank-standing-order-service', port: 8121 },
  'swift-service':          { container: 'openbank-swift-service',          port: 8122 },
  'sanctions-service':      { container: 'openbank-sanctions-service',      port: 8123 },
  'clearing-service':       { container: 'openbank-clearing-service',       port: 8124 },
  'interest-service':       { container: 'openbank-interest-service',       port: 8125 },
  'lending-service':        { container: 'openbank-lending-service',        port: 8126 },
  'campaign-service':       { container: 'openbank-campaign-service',       port: 8128 },
  'sdd-service':            { container: 'openbank-sdd-service',            port: 8129 },
  'fraud-service':          { container: 'openbank-fraud-service',          port: 8133 },
  'dispute-service':        { container: 'openbank-dispute-service',        port: 8135 },
  'sepa-instant':           { container: 'openbank-sepa-instant',           port: 8127 },
  'document-service':       { container: 'openbank-document-service',       port: 8143 },
  'engagement-service':     { container: 'openbank-engagement-service',     port: 8153 },
  // ADR-0097: the regulatory console reads the implemented FINREP/COREP templates
  // through this same operator-token BFF path. Without this entry, a healthy
  // finrep-service is invisible to the browser and the page can only show mock data.
  'finrep-service':         { container: 'openbank-finrep-service',         port: 8140 },
  'vop-service':            { container: 'openbank-vop-service',            port: 8149 },
}

// In-cluster, the upstream address must be the real Service DNS
// (`<name>.<namespace>.svc:<port>`). The legacy static map only knew compose
// container names / localhost, so with no SERVICES_HOST set the proxy resolved
// every backend to `localhost:81xx` — i.e. the admin-ui pod itself — and the
// Accounts/Ledger/Transactions screens (and the operator's create-account flow)
// got connection-refused. We resolve via the same ADR-0051 discovery feed as
// System Health, cached briefly so the hot proxy path doesn't hit the K8s API on
// every request while still picking up new services within the TTL.
// A resolved upstream target. `scaledToZero` means the Deployment exists but is
// idle at zero replicas (KEDA scale-to-zero, ADR-0057) — distinct from null
// (undeployed). The proxy short-circuits a scaled-to-zero target with a 503 so
// the UI shows "idle, will wake" instead of "not deployed" or a 10s timeout.
type ResolvedService = { url: string; scaledToZero: boolean }

let discoveryCache:
  | { at: number; map: Record<string, { namespace: string; port: number; scaledToZero: boolean }> }
  | null = null
const DISCOVERY_TTL_MS = 30_000

async function resolveInCluster(svcKey: string): Promise<ResolvedService | null> {
  const now = Date.now()
  if (!discoveryCache || now - discoveryCache.at > DISCOVERY_TTL_MS) {
    const discovered = await discoverServices()
    if (discovered) {
      const map: Record<string, { namespace: string; port: number; scaledToZero: boolean }> = {}
      for (const d of discovered) map[d.name] = { namespace: d.namespace, port: d.port, scaledToZero: d.scaledToZero }
      discoveryCache = { at: now, map }
    }
  }
  const hit = discoveryCache?.map[svcKey]
  return hit ? { url: `http://${svcKey}.${hit.namespace}.svc:${hit.port}`, scaledToZero: hit.scaledToZero } : null
}

async function serviceBaseUrl(svcKey: string): Promise<ResolvedService | null> {
  if (svcKey === 'product-catalog' && process.env.CATALOG_STANDALONE_SIDECAR === 'true') {
    return { url: 'http://127.0.0.1:8104', scaledToZero: false }
  }
  if (inCluster()) {
    // Only proxy to services the cluster actually exposes; an undeployed service
    // resolves to null → 404 rather than a misleading hang.
    return resolveInCluster(svcKey)
  }
  // Off-cluster (local dev / docker-compose): legacy localhost/container map.
  const svc = SERVICE_MAP[svcKey]
  if (!svc) return null
  const host =
    process.env.SERVICES_HOST === 'container'
      ? svc.container
      : (process.env.SERVICES_HOST ?? 'localhost')
  return { url: `http://${host}:${svc.port}`, scaledToZero: false }
}

const FORWARD_HEADERS = [
  'content-type', 'accept', 'authorization',
  'idempotency-key', 'if-match', 'x-request-id', 'x-correlation-id',
  // ADR-0155/ADR-0176: the maker's four-eyes retry carries this header once a checker has
  // approved (AuthorizeInterceptor). Without forwarding it, every retry looks identical to the
  // original request and gets 202'd again forever.
  'x-approval-id',
  // NOTE: x-operator-id is deliberately NOT forwarded from the client. It is
  // derived server-side from the session below — see the comment there.
]

export const dynamic = 'force-dynamic'

async function proxy(
  req: NextRequest,
  service: string,
  pathSegments: string[],
): Promise<NextResponse> {
  const resolved = await serviceBaseUrl(service)
  if (!resolved) {
    return NextResponse.json({ error: `Unknown service: ${service}` }, { status: 404 })
  }
  // Deployed but idle at zero replicas (KEDA scale-to-zero, ADR-0057). The pod
  // would be unreachable, so short-circuit with a distinct signal rather than a
  // 10s timeout that reads as a failure. The UI surfaces a calm "idle" state.
  if (resolved.scaledToZero) {
    return NextResponse.json({ error: 'scaled_to_zero' }, { status: 503 })
  }

  const upstreamPath = '/' + pathSegments.join('/')
  const search = req.nextUrl.search
  const upstreamUrl = `${resolved.url}${upstreamPath}${search}`

  const headers = new Headers()
  for (const h of FORWARD_HEADERS) {
    const v = req.headers.get(h)
    if (v) headers.set(h, v)
  }

  // BFF token relay: the browser authenticates to admin-ui with a NextAuth session
  // cookie, not a bearer — so the proxied call carries no Authorization and every
  // @RolesAllowed backend (AccountResource, etc.) answers 401. Inject the session's
  // Keycloak access token (which carries the operator's ROLE_* claims) as the
  // upstream bearer, so backend RBAC sees the real caller. We never overwrite an
  // explicit Authorization already on the request (service-to-service / tests).
  const session = await auth()
  if (!headers.has('authorization')) {
    const accessToken = session?.user?.accessToken
    if (accessToken) headers.set('authorization', `Bearer ${accessToken}`)
  }

  // The audited human behind a mutation. Several backends take it as a required
  // @HeaderParam — card-issuance's CardResource stamps it onto every
  // CardStatusChanged event — so without it an operator action is anonymous, or a
  // 400, since the parameter is non-nullable there.
  //
  // DERIVED from the session, never forwarded from the request. A browser can set
  // any header it likes, so trusting the client here would let an operator write
  // someone else's name into the audit trail of a card block — an audit field the
  // audited party controls is not an audit field. The header is stripped from
  // FORWARD_HEADERS above precisely so a spoofed one cannot survive this hop.
  const operator = session?.user?.email ?? session?.user?.name
  if (operator) headers.set('x-operator-id', `admin-ui:${operator}`)

  // Edge guard (ADR-0056): the BFF must never be an unauthenticated relay into
  // the cluster. If the caller presented neither an explicit bearer (service-to-
  // service / tests) nor a valid operator session, refuse before touching any
  // backend. This is defense-in-depth on top of each service's own RBAC — a
  // backend that hasn't been auth-gated yet (e.g. product-catalog) would
  // otherwise be reachable anonymously through this proxy.
  if (!headers.has('authorization')) {
    return NextResponse.json({ error: 'unauthorized' }, { status: 401 })
  }

  const hasBody = req.method !== 'GET' && req.method !== 'HEAD'
  const body = hasBody ? await req.arrayBuffer() : undefined

  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 10_000)
    const upstream = await fetch(upstreamUrl, {
      method: req.method,
      headers,
      body: body?.byteLength ? body : undefined,
      signal: ctrl.signal,
      cache: 'no-store',
    })
    clearTimeout(timer)

    const responseHeaders = new Headers()
    const ct = upstream.headers.get('content-type')
    if (ct) responseHeaders.set('content-type', ct)
    const etag = upstream.headers.get('etag')
    if (etag) responseHeaders.set('etag', etag)
    responseHeaders.set('cache-control', 'no-store')

    const data = await upstream.arrayBuffer()
    return new NextResponse(data, { status: upstream.status, headers: responseHeaders })
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : String(err)
    return NextResponse.json({ error: 'upstream_unreachable', detail: msg }, { status: 502 })
  }
}

type RouteContext = { params: Promise<{ service: string; path: string[] }> }

export async function GET(req: NextRequest, { params }: RouteContext) {
  const { service, path } = await params
  return proxy(req, service, path ?? [])
}
export async function POST(req: NextRequest, { params }: RouteContext) {
  const { service, path } = await params
  return proxy(req, service, path ?? [])
}
export async function PUT(req: NextRequest, { params }: RouteContext) {
  const { service, path } = await params
  return proxy(req, service, path ?? [])
}
export async function PATCH(req: NextRequest, { params }: RouteContext) {
  const { service, path } = await params
  return proxy(req, service, path ?? [])
}
export async function DELETE(req: NextRequest, { params }: RouteContext) {
  const { service, path } = await params
  return proxy(req, service, path ?? [])
}
