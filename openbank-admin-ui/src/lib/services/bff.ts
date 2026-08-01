// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0056 — the admin-ui BFF is the ONLY path from the operator's browser to a
// cluster service. The browser never talks to `http://localhost:<port>` or an
// in-cluster `…​.svc` DNS name: those are unreachable from the operator's
// machine (→ ERR_CONNECTION_REFUSED) and would bypass the session→bearer relay
// and backend RBAC. Instead every live call is same-origin to
// `/api/svc/<k8s-service-name>/<path>`, which the proxy
// (src/app/api/svc/[service]/[...path]/route.ts) authenticates, resolves via
// ADR-0051 discovery and forwards with the operator's Keycloak access token.
//
// The canonical service key is the **Kubernetes Deployment / Service name**
// (e.g. `account-service`, `product-catalog`) — the same key the proxy's
// SERVICE_MAP and the discovery feed use. UI-local short ids (`account`,
// `catalog`, `sepa`, …) must be resolved to this key before building a BFF URL.

export const BFF_PREFIX = '/api/svc'

/**
 * Build a same-origin BFF URL for a cluster service.
 *
 * @param k8sName Kubernetes Deployment/Service name (e.g. `account-service`).
 * @param path    Upstream path on the service (e.g. `/q/openapi`, `/api/v1/info`).
 * @param query   Optional query parameters, forwarded verbatim by the proxy.
 */
export function svcUrl(
  k8sName: string,
  path: string,
  query?: Record<string, string>,
): string {
  const p = path.startsWith('/') ? path : `/${path}`
  const qs = query && Object.keys(query).length ? `?${new URLSearchParams(query).toString()}` : ''
  return `${BFF_PREFIX}/${encodeURIComponent(k8sName)}${p}${qs}`
}

/** The monorepo folder / release-please component for a service, e.g.
 *  `account-service` → `openbank-account-service`. Used by the docs BFF
 *  (/api/docs/...) to locate per-service CHANGELOG / release tags. */
/**
 * Absolute upstream URL for a cluster service, for use in SERVER code (route handlers).
 *
 * [svcUrl] returns a same-origin *relative* path, which is right for the browser and wrong here:
 * Node's `fetch` rejects a relative URL outright with `Failed to parse URL from /api/svc/…`. That
 * throw is easy to swallow in a `catch` and surface as "the service did not answer", which is how
 * the campaign console reported a healthy service as down (#2749).
 *
 * In-cluster we address the Service DNS directly — the proxy exists to give the *browser* a
 * same-origin path, and server code has no such constraint. Off-cluster we fall back to the same
 * localhost convention the proxy uses for local dev.
 */
export function serverSvcUrl(
  k8sName: string,
  namespace: string,
  port: number,
  path: string,
  query?: Record<string, string>,
): string {
  const p = path.startsWith('/') ? path : `/${path}`
  const qs = query && Object.keys(query).length ? `?${new URLSearchParams(query).toString()}` : ''
  const host = process.env.KUBERNETES_SERVICE_HOST
    ? `${k8sName}.${namespace}.svc:${port}`
    : `${process.env.SERVICES_HOST ?? 'localhost'}:${port}`
  return `http://${host}${p}${qs}`
}

export function componentFolder(k8sName: string): string {
  return k8sName.startsWith('openbank-') ? k8sName : `openbank-${k8sName}`
}

/**
 * Why a BFF call failed, distinguished so a page can degrade to a meaningful
 * empty state instead of surfacing a raw "HTTP 404" (which an operator reads as
 * "the app is broken" when in fact the target service simply isn't deployed in
 * this environment — much of the fleet is not in the sandbox).
 *
 * The proxy (src/app/api/svc/[service]/[...path]/route.ts) emits a stable JSON
 * `{ error }` body per case, so we can tell them apart reliably:
 *   - 404 `{error:"Unknown service: <svc>"}` → not deployed / not discovered
 *   - 503 `{error:"scaled_to_zero"}`         → deployed but idle at 0 replicas (KEDA, ADR-0057)
 *   - 401 `{error:"unauthorized"}`           → no operator session / bearer
 *   - 502 `{error:"upstream_unreachable"}`   → deployed but the pod didn't answer
 * A 404 WITHOUT that body is a genuine backend 404 (unknown endpoint or id).
 */
export type BffFailure =
  | 'not_deployed'
  | 'scaled_to_zero'
  | 'unauthorized'
  | 'unreachable'
  | 'not_found'
  | 'error'

/**
 * Classify a non-OK BFF `Response`. Clones the response before reading so the
 * caller can still consume the original body if it wants to. Never throws.
 */
export async function classifyBffFailure(res: Response): Promise<BffFailure> {
  let error = ''
  try {
    const body = (await res.clone().json()) as { error?: unknown }
    if (typeof body?.error === 'string') error = body.error
  } catch {
    // non-JSON body (e.g. an HTML error page) — fall through to status-only
  }
  if (res.status === 404 && error.startsWith('Unknown service')) return 'not_deployed'
  if (res.status === 503 && error === 'scaled_to_zero') return 'scaled_to_zero'
  if (res.status === 401) return 'unauthorized'
  if (res.status === 502 && error === 'upstream_unreachable') return 'unreachable'
  if (res.status === 404) return 'not_found'
  return 'error'
}
