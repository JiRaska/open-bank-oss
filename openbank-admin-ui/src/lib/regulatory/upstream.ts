// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server-side source client for the regulatory report generators (/api/regulatory/returns/**).
//
// Bearer-relay rule (admin-ui CLAUDE.md #7): every upstream read carries the operator's own
// Keycloak token, so each backend's @RolesAllowed/@Authorize decides what this operator may
// aggregate — the BFF never becomes a privileged relay. Base URLs resolve exactly like the
// generic /api/svc proxy: the catalog allowlist gates the key, ADR-0051 discovery in-cluster,
// the localhost/container map off-cluster. Upstream bodies never reach the browser; failures
// become a typed `ReportSourceError` and the detail stays in the server log.

import 'server-only'
import { inCluster, resolveInClusterBaseUrl } from '@/lib/discovery'
import { getProxyAllowlist } from '@/lib/services/fleet'
import { ReportSourceError, type ReportSourceClient } from '@/lib/regulatory/generatedReports'

async function baseUrl(service: string): Promise<string> {
  const allowlist = getProxyAllowlist()
  if (!Object.hasOwn(allowlist, service)) throw new ReportSourceError('not_deployed', service, 'not in the service catalog')
  if (inCluster()) {
    const url = await resolveInClusterBaseUrl(service)
    if (!url) throw new ReportSourceError('not_deployed', service, 'not deployed in this cluster')
    return url
  }
  const svc = allowlist[service]
  const host = process.env.SERVICES_HOST === 'container' ? svc.container : (process.env.SERVICES_HOST ?? 'localhost')
  return `http://${host}:${svc.port}`
}

export function createReportSourceClient(accessToken: string): ReportSourceClient {
  async function request(service: string, method: 'GET' | 'POST', path: string, options: { query?: Record<string, string>; body?: unknown } = {}) {
    const base = await baseUrl(service)
    const qs = options.query && Object.keys(options.query).length ? `?${new URLSearchParams(options.query).toString()}` : ''
    let res: Response
    try {
      res = await fetch(`${base}${path}${qs}`, {
        method,
        headers: {
          Authorization: `Bearer ${accessToken}`,
          Accept: 'application/json',
          ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        },
        body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
        cache: 'no-store',
        signal: AbortSignal.timeout(method === 'POST' ? 60_000 : 15_000),
      })
    } catch (err) {
      console.error(`regulatory BFF: ${method} ${service}${path} unreachable:`, err)
      throw new ReportSourceError('unreachable', service, 'did not answer')
    }
    if (res.status === 204) return { status: 204, body: null }
    const raw = await res.text().catch(() => '')
    if (!res.ok) {
      console.error(`regulatory BFF: ${method} ${service}${path} -> ${res.status} ${raw.slice(0, 500)}`)
      return { status: res.status, body: null }
    }
    try {
      return { status: res.status, body: raw ? JSON.parse(raw) as unknown : null }
    } catch {
      throw new ReportSourceError('error', service, 'non-JSON response')
    }
  }

  return {
    request,
    async get(service, path, query) {
      const res = await request(service, 'GET', path, { query })
      if (res.status >= 200 && res.status < 300) return res.body
      throw new ReportSourceError(res.status === 401 || res.status === 403 ? 'unauthorized' : 'error', service, `HTTP ${res.status}`)
    },
  }
}
