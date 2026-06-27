// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextResponse } from 'next/server'
import { discoverServices } from '@/lib/discovery'

export const dynamic = 'force-dynamic'

interface ServiceDef {
  name: string
  port: number
  containerName: string
  /** management port for /q/health (most services use 8085) */
  mgmtPort?: number
}

const SERVICES: ServiceDef[] = [
  { name: 'account-service',        port: 8100, containerName: 'openbank-account-service',        mgmtPort: 8085 },
  { name: 'ledger-service',         port: 8101, containerName: 'openbank-ledger-service',          mgmtPort: 8085 },
  { name: 'transaction-service',    port: 8102, containerName: 'openbank-transaction-service',     mgmtPort: 8085 },
  { name: 'balance-service',        port: 8103, containerName: 'openbank-balance-service',         mgmtPort: 8085 },
  { name: 'product-catalog',        port: 8104, containerName: 'openbank-product-catalog' },
  { name: 'pid-service',            port: 8105, containerName: 'openbank-pid-service',             mgmtPort: 8085 },
  { name: 'consent-service',        port: 8106, containerName: 'openbank-consent-service',         mgmtPort: 8085 },
  { name: 'psd2-service',           port: 8107, containerName: 'openbank-psd2-service',            mgmtPort: 8085 },
  { name: 'tpp-registry-service',   port: 8108, containerName: 'openbank-tpp-registry-service',    mgmtPort: 8085 },
  { name: 'agent-service',          port: 8109, containerName: 'openbank-agent-service' },
  { name: 'sca-service',            port: 8110, containerName: 'openbank-sca-service',             mgmtPort: 8085 },
  { name: 'party-service',          port: 8111, containerName: 'openbank-party-service',           mgmtPort: 8085 },
  { name: 'notification-service',   port: 8112, containerName: 'openbank-notification-service',    mgmtPort: 8085 },
  { name: 'audit-service',          port: 8113, containerName: 'openbank-audit-service',           mgmtPort: 8085 },
  { name: 'kyc-service',            port: 8114, containerName: 'openbank-kyc-service',             mgmtPort: 8085 },
  { name: 'sepa-payment',           port: 8115, containerName: 'openbank-sepa-payment',            mgmtPort: 8085 },
  { name: 'domestic-payment',       port: 8116, containerName: 'openbank-domestic-payment',        mgmtPort: 8085 },
  { name: 'aml-service',            port: 8117, containerName: 'openbank-aml-service',             mgmtPort: 8085 },
  { name: 'card-issuance-service',  port: 8118, containerName: 'openbank-card-issuance-service',   mgmtPort: 8085 },
  { name: 'fx-service',             port: 8119, containerName: 'openbank-fx-service',              mgmtPort: 8085 },
  { name: 'security-scanner',       port: 8120, containerName: 'openbank-security-scanner',        mgmtPort: 8085 },
  { name: 'standing-order-service', port: 8121, containerName: 'openbank-standing-order-service',  mgmtPort: 8085 },
  { name: 'swift-service',          port: 8122, containerName: 'openbank-swift-service',           mgmtPort: 8085 },
  { name: 'sanctions-service',      port: 8123, containerName: 'openbank-sanctions-service',       mgmtPort: 8085 },
  { name: 'clearing-service',       port: 8124, containerName: 'openbank-clearing-service',        mgmtPort: 8085 },
  { name: 'interest-service',       port: 8125, containerName: 'openbank-interest-service',        mgmtPort: 8085 },
  { name: 'dispute-service',        port: 8135, containerName: 'openbank-dispute-service',         mgmtPort: 8085 },
  { name: 'sepa-instant-service',   port: 8127, containerName: 'openbank-sepa-instant-service',    mgmtPort: 8085 },
]

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
  const results = await Promise.all(SERVICES.map(probeService))
  return NextResponse.json(results)
}
