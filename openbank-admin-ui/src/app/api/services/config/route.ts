// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { discoverServices } from '@/lib/discovery'
import { getRegistry } from '@/lib/services/fleet'

export const dynamic = 'force-dynamic'

interface ServiceDef {
  name: string
  port: number
  containerName: string
  /** management port for /q/health (most services use 8085) */
  mgmtPort?: number
}

// Off-cluster probe list, DERIVED from the code-generated catalog (see @/lib/services/fleet).
function staticServices(): ServiceDef[] {
  return getRegistry().map(s => ({
    name: s.container.replace(/^openbank-/, ''),
    port: s.port,
    containerName: s.container,
    mgmtPort: s.mgmtPort,
  }))
}

function resolveHost(containerName: string): string {
  if (process.env.SERVICES_HOST === 'container') return containerName
  return 'localhost'
}

async function fetchJson<T>(url: string, timeoutMs = 8000): Promise<T | null> {
  try {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), timeoutMs)
    const res = await fetch(url, { signal: ctrl.signal, cache: 'no-store' })
    clearTimeout(t)
    if (!res.ok) return null
    return await res.json() as T
  } catch {
    return null
  }
}

async function probeService(svc: ServiceDef) {
  const host = resolveHost(svc.containerName)
  const appBase = `http://${host}:${svc.port}`
  const mgmtBase = svc.mgmtPort ? `http://${host}:${svc.mgmtPort}` : appBase
  const start = Date.now()

  const [config, healthApp, healthMgmt] = await Promise.all([
    fetchJson<unknown>(`${appBase}/api/v1/config`),
    fetchJson<{ status: string; checks?: { name: string; status: string }[] }>(`${appBase}/q/health`),
    svc.mgmtPort
      ? fetchJson<{ status: string; checks?: { name: string; status: string }[] }>(`${mgmtBase}/q/health`)
      : Promise.resolve(null),
  ])

  const latencyMs = Date.now() - start
  const health = healthMgmt ?? healthApp
  const reachable = health !== null || config !== null

  return {
    name: svc.name,
    port: svc.port,
    config: config ?? null,
    health,
    latencyMs,
    reachable,
  }
}

// In-cluster (ADR-0051): the same Kubernetes discovery that drives System Health
// is the authoritative inventory + address source here too. The previous static
// list addressed `localhost:81xx`, which inside the pod resolves to the admin-ui
// container itself — every probe failed and the page read "0 healthy". We now
// reach each service on its real Service DNS, take readiness from the K8s control
// plane (a sibling pod can't hit the management /q/health port), and enrich the
// live resilience policy from /api/v1/config on the business port.
async function probeDiscovered(d: { name: string; namespace: string; port: number; ready: boolean; readyReplicas: number }) {
  const base = `http://${d.name}.${d.namespace}.svc:${d.port}`
  const start = Date.now()
  const [config, health] = await Promise.all([
    fetchJson<unknown>(`${base}/api/v1/config`),
    fetchJson<{ status: string; checks?: { name: string; status: string }[] }>(`${base}/q/health`),
  ])
  const latencyMs = Date.now() - start
  // Readiness verdict comes from the control plane; /q/health (often on a separate,
  // non-Service-exposed management port) only enriches the per-check breakdown.
  const status = d.ready ? 'UP' : 'DOWN'
  return {
    name: d.name,
    port: d.port,
    config: config ?? null,
    health: { status, checks: health?.checks ?? [] },
    latencyMs,
    reachable: d.readyReplicas > 0,
  }
}

export async function GET() {
  const discovered = await discoverServices()
  if (discovered) {
    const results = await Promise.all(discovered.map(probeDiscovered))
    return NextResponse.json(results)
  }
  const results = await Promise.all(staticServices().map(probeService))
  return NextResponse.json(results)
}
