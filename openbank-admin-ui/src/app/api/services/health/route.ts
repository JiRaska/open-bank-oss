// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import { NextResponse } from 'next/server'
import { discoverServices, prettyLabel } from '@/lib/discovery'

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

const SERVICES: Omit<ServiceHealthEntry, 'status' | 'reachable' | 'latencyMs'>[] = [
  { name: 'account-service',        port: 8100, label: 'Accounts',        group: 'core',         container: 'openbank-account-service',        healthPath: '/api/v1/accounts?partyId=00000000-0000-0000-0000-000000000001' },
  { name: 'ledger-service',         port: 8101, label: 'Ledger',          group: 'core',         container: 'openbank-ledger-service',          healthPath: '/api/v1/journals' },
  { name: 'transaction-service',    port: 8102, label: 'Transactions',    group: 'core',         container: 'openbank-transaction-service',     healthPath: '/api/v1/transactions?accountId=00000000-0000-0000-0000-000000000001' },
  { name: 'balance-service',        port: 8103, label: 'Balance',         group: 'core',         container: 'openbank-balance-service',         healthPath: '/api/v1/balances/00000000-0000-0000-0000-000000000001' },
  { name: 'product-catalog',        port: 8104, label: 'Product Catalog', group: 'core',         container: 'openbank-product-catalog',         healthPath: '/api/v1/products' },
  { name: 'pid-service',            port: 8105, label: 'PID',             group: 'identity',     container: 'openbank-pid-service',             healthPath: '/api/v1/parties' },
  { name: 'consent-service',        port: 8106, label: 'Consent',         group: 'open-banking', container: 'openbank-consent-service',         healthPath: '/api/v1/consents' },
  { name: 'psd2-service',           port: 8107, label: 'PSD2',            group: 'open-banking', container: 'openbank-psd2-service',            healthPath: '/open-banking/v2/accounts' },
  { name: 'tpp-registry-service',   port: 8108, label: 'TPP Registry',    group: 'open-banking', container: 'openbank-tpp-registry-service',    healthPath: '/api/v1/tpp-registry/check?tppId=probe&role=AISP' },
  { name: 'agent-service',          port: 8109, label: 'Agent (MCP)',     group: 'platform',     container: 'openbank-agent-service',           healthPath: '/api/v1/tools' },
  { name: 'sca-service',            port: 8110, label: 'SCA',             group: 'identity',     container: 'openbank-sca-service',             healthPath: '/api/v1/sca/challenges' },
  { name: 'party-service',          port: 8111, label: 'Parties',         group: 'identity',     container: 'openbank-party-service',           healthPath: '/api/v1/parties' },
  { name: 'notification-service',   port: 8112, label: 'Notifications',   group: 'platform',     container: 'openbank-notification-service',    healthPath: '/api/v1/notifications' },
  { name: 'audit-service',          port: 8113, label: 'Audit',           group: 'compliance',   container: 'openbank-audit-service',           healthPath: '/api/v1/audit/entries/00000000-0000-0000-0000-000000000001' },
  { name: 'kyc-service',            port: 8114, label: 'KYC',             group: 'compliance',   container: 'openbank-kyc-service',             healthPath: '/api/v1/kyc/cases' },
  { name: 'sepa-payment',           port: 8115, label: 'SEPA',            group: 'payments',     container: 'openbank-sepa-payment',            healthPath: '/api/v1/sepa-payments' },
  { name: 'domestic-payment',       port: 8116, label: 'Domestic',        group: 'payments',     container: 'openbank-domestic-payment',        healthPath: '/api/v1/domestic-payments' },
  { name: 'aml-service',            port: 8117, label: 'AML',             group: 'compliance',   container: 'openbank-aml-service',             healthPath: '/api/v1/aml/cases' },
  { name: 'card-issuance-service',  port: 8118, label: 'Cards',           group: 'payments',     container: 'openbank-card-issuance-service',   healthPath: '/api/v1/cards' },
  { name: 'fx-service',             port: 8119, label: 'FX',              group: 'payments',     container: 'openbank-fx-service',              healthPath: '/api/v1/fx/rates' },
  { name: 'security-scanner',       port: 8120, label: 'Security',        group: 'platform',     container: 'openbank-security-scanner',        healthPath: '/api/v1/security/report' },
  { name: 'standing-order-service', port: 8121, label: 'Standing Orders', group: 'payments',     container: 'openbank-standing-order-service',  healthPath: '/api/v1/standing-orders' },
  { name: 'swift-service',          port: 8122, label: 'SWIFT',           group: 'payments',     container: 'openbank-swift-service',           healthPath: '/api/v1/swift/messages' },
  { name: 'sanctions-service',      port: 8123, label: 'Sanctions',       group: 'compliance',   container: 'openbank-sanctions-service',       healthPath: '/api/v1/sanctions' },
  { name: 'clearing-service',       port: 8124, label: 'Clearing',        group: 'payments',     container: 'openbank-clearing-service',        healthPath: '/api/v1/clearing/batches' },
  { name: 'interest-service',       port: 8125, label: 'Interest',        group: 'payments',     container: 'openbank-interest-service',        healthPath: '/api/v1/interest/accruals' },
  { name: 'dispute-service',        port: 8135, label: 'Disputes',        group: 'compliance',   container: 'openbank-dispute-service',         healthPath: '/api/v1/disputes' },
  { name: 'sepa-instant',           port: 8127, label: 'SEPA Instant',    group: 'payments',     container: 'openbank-sepa-instant',            healthPath: '/api/v1/sepa-instant' },
  // Extended / reporting — were missing from the probe list before ADR-0071.
  { name: 'lending-service',        port: 8128, label: 'Lending',         group: 'core',         container: 'openbank-lending-service',         healthPath: '/api/v1/loans' },
  { name: 'statement-service',      port: 8136, label: 'Statements',      group: 'core',         container: 'openbank-statement-service',       healthPath: '/api/v1/statements' },
  { name: 'onboarding-service',     port: 8130, label: 'Onboarding',      group: 'identity',     container: 'openbank-onboarding-service',      healthPath: '/api/v1/onboarding' },
  { name: 'anacredit-service',      port: 8137, label: 'AnaCredit',       group: 'compliance',   container: 'openbank-anacredit-service',       healthPath: '/api/v1/anacredit' },
  { name: 'sdd-service',            port: 8132, label: 'SEPA DD',         group: 'payments',     container: 'openbank-sdd-service',             healthPath: '/api/v1/sdd/mandates' },
]

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
  const services = discovered ?? (await Promise.all(SERVICES.map(probeService)))
  const source = discovered ? 'kubernetes' : 'static'

  const byContainer: Record<string, ServiceHealthEntry> = {}
  for (const s of services) byContainer[s.container] = s

  return NextResponse.json(
    { services, byContainer, source },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
