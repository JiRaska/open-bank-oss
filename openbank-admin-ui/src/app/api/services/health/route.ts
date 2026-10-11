// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { discoverServices, prettyLabel } from '@/lib/discovery'
import { getRegistry } from '@/lib/services/fleet'

export interface ServiceHealthEntry {
  name: string
  port: number
  label: string
  group: 'core' | 'payments' | 'compliance' | 'identity' | 'open-banking' | 'platform'
  container: string
  healthPath: string
  status: 'UP' | 'DOWN' | 'UNKNOWN'
  reachable: boolean
  latencyMs: number | null
  version?: string | null
  gitCommit?: string | null
  /** Tech-stack snapshot, fetched separately from /api/v1/info. Null if probe failed. */
  stack?: ServiceStackEntry | null
}

export interface ServiceStackEntry {
  kotlin?:  { version: string }
  quarkus?: { version: string; lts?: boolean; supportUntil?: string }
  java?:    { version: string; vendor?: string; arch?: string; cpu?: number; maxHeapMib?: number }
  gradle?:  { version: string }
  libs?:    { version: string; buildTime?: string; gitCommit?: string }
}

// Off-cluster static probe list: DERIVED from the code-generated catalog (see
// @/lib/services/fleet), never typed here. In-cluster the inventory comes from Kubernetes discovery.
function staticServices(): Omit<ServiceHealthEntry, 'status' | 'reachable' | 'latencyMs'>[] {
  return getRegistry().map(s => ({
    name: s.container.replace(/^openbank-/, ''),
    port: s.port,
    label: s.label,
    group: s.group,
    container: s.container,
    healthPath: HEALTH_PATH,
  }))
}

// Liveness/readiness is probed via the Quarkus SmallRye health endpoint that
// every service exposes through openbank-libs — auth-free, state-free, and
// uniform across the fleet. The earlier per-service *business* endpoints
// (e.g. /api/v1/journals) required auth/valid UUIDs/DB state and returned
// 4xx/5xx on a healthy-but-empty service, which the old broad
// HEALTHY_STATUS_CODES set papered over and a 500 still flipped to DOWN.
// `/q/health/ready` returns 200 when UP and 503 when DOWN — a clean signal.
const HEALTH_PATH = '/q/health/ready'

export const dynamic = 'force-dynamic'
export const revalidate = 0

async function probeInfo(base: string): Promise<{ version?: string; gitCommit?: string; stack?: ServiceStackEntry } | null> {
  // /api/v1/info is served by every service via openbank-libs.web.ServiceInfoResource
  // (SBOM-2). The response carries the BuildInfo stack snapshot which we render
  // in the system/health UI.
  //
  // Timeout: 8 seconds. The earlier 2s was too aggressive — under load (fleet
  // rebuild on the same machine, or just the moment after a service starts
  // when JIT is still warming up) the call legitimately takes 3-6s and a 2s
  // cutoff manifested as "N/A everywhere" in the Tech Stack chips. The call
  // itself is cheap (BuildInfo singleton cached at JVM startup); the wall-
  // clock cost is dominated by JVM scheduling under contention.
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 8000)
    const res = await fetch(`${base}/api/v1/info`, { signal: ctrl.signal, cache: 'no-store' })
    clearTimeout(timer)
    if (!res.ok) return null
    const body = await res.json() as { version?: string; gitCommit?: string; stack?: ServiceStackEntry }
    return { version: body.version, gitCommit: body.gitCommit, stack: body.stack }
  } catch {
    return null
  }
}

async function probeService(
  svc: Omit<ServiceHealthEntry, 'status' | 'reachable' | 'latencyMs'>,
): Promise<ServiceHealthEntry> {
  const host = process.env.SERVICES_HOST === 'container' ? svc.container : (process.env.SERVICES_HOST ?? 'localhost')
  const base = `http://${host}:${svc.port}`
  const start = Date.now()

  // Health probe and info probe run in parallel — info probe is lightweight (cached
  // BuildInfo at JVM startup) and short-timeout so it does not block the health view.
  const [healthResult, infoResult] = await Promise.all([
    (async () => {
      try {
        const ctrl = new AbortController()
        const timer = setTimeout(() => ctrl.abort(), 8000)
        const res = await fetch(`${base}${HEALTH_PATH}`, { signal: ctrl.signal, cache: 'no-store' })
        clearTimeout(timer)
        // Reachable = we got an HTTP response at all (200 UP, 503 DOWN-but-alive).
        return { ok: true, status: res.status }
      } catch {
        // Connection refused / DNS failure / timeout — the service is not reachable.
        return { ok: false, status: 0 }
      }
    })(),
    probeInfo(base),
  ])

  if (!healthResult.ok) {
    return { ...svc, status: 'DOWN', reachable: false, latencyMs: null, stack: infoResult?.stack ?? null }
  }
  const latencyMs = Date.now() - start
  // SmallRye health: 200 ⇒ UP, anything else (503) ⇒ reachable but DOWN.
  const status: ServiceHealthEntry['status'] = healthResult.status === 200 ? 'UP' : 'DOWN'
  return {
    ...svc,
    status,
    reachable: true,
    latencyMs,
    version: infoResult?.version ?? null,
    gitCommit: infoResult?.gitCommit ?? null,
    stack: infoResult?.stack ?? null,
  }
}

// In-cluster, the authoritative inventory + health comes from the Kubernetes API
// (ADR-0051): we list Deployments in the OpenBank domain namespaces and read each
// one's readiness — the kubelet's own /q/health/ready verdict on the management
// port, which a sibling pod cannot reach directly. Version/stack chips are still
// enriched best-effort from /api/v1/info on the Service DNS. Off-cluster (local
// dev) discoverServices() returns null and we fall back to static probing.
async function fromDiscovery(): Promise<ServiceHealthEntry[] | null> {
  const discovered = await discoverServices()
  if (!discovered) return null

  return Promise.all(
    discovered.map(async (d): Promise<ServiceHealthEntry> => {
      const base = `http://${d.name}.${d.namespace}.svc:${d.port}`
      const reachable = d.readyReplicas > 0
      // Only enrich running services; a probe against a down service just times out.
      const info = reachable ? await probeInfo(base) : null
      return {
        name: d.name,
        port: d.port,
        label: prettyLabel(d.name),
        group: d.group,
        container: d.name,
        healthPath: '', // inert: readiness comes from the K8s control plane, not a probe path
        status: d.ready ? 'UP' : 'DOWN',
        reachable,
        latencyMs: null,
        version: info?.version ?? null,
        gitCommit: info?.gitCommit ?? null,
        stack: info?.stack ?? null,
      }
    }),
  )
}

export async function GET() {
  const discovered = await fromDiscovery()
  const services = discovered ?? (await Promise.all(staticServices().map(probeService)))
  const source = discovered ? 'kubernetes' : 'static'

  const byContainer: Record<string, ServiceHealthEntry> = {}
  for (const s of services) byContainer[s.container] = s

  return NextResponse.json(
    { services, byContainer, source },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
