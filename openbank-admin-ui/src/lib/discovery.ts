// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Generic, label-free service discovery via the Kubernetes API (ADR-0051).
//
// Instead of a hardcoded list of services baked into the web tier, the admin UI
// asks the cluster what exists: it lists Deployments in the OpenBank domain
// namespaces and derives health from each Deployment's readiness — which IS the
// kubelet's own /q/health/ready result on the management port, re-published by
// the control plane. New services appear automatically the moment their manifest
// is applied; nothing here needs editing per service.
//
// Off-cluster (local dev / docker-compose) there is no ServiceAccount token, so
// discoverServices() returns null and callers fall back to the static probe.

import { readFileSync, existsSync } from 'node:fs'
import https from 'node:https'

const SA_DIR = '/var/run/secrets/kubernetes.io/serviceaccount'
const TOKEN_PATH = `${SA_DIR}/token`
const CA_PATH = `${SA_DIR}/ca.crt`

export type ServiceGroup =
  | 'core' | 'payments' | 'compliance' | 'identity' | 'open-banking' | 'platform'

export interface DiscoveredService {
  name: string
  namespace: string
  port: number
  group: ServiceGroup
  /** All desired replicas are ready (the kubelet's /q/health/ready is green). */
  ready: boolean
  replicas: number
  readyReplicas: number
  /**
   * Deployed but intentionally at zero replicas — a KEDA scale-to-zero service
   * (ADR-0057) that is idle, NOT undeployed and NOT crash-looping. Distinguishes
   * "asleep, will wake on demand" from "not deployed here" and "deployed but down".
   */
  scaledToZero: boolean
}

// Namespace → UI group. Adding a whole new domain namespace requires THREE steps
// (ADR-0051, "new namespace checklist") — no code deploy needed after this:
//   1. Add it here (determines the group chip in System Health).
//   2. Add it to OPENBANK_NAMESPACES in gitops/components/admin-ui/admin-ui.yaml.
//   3. Add a RoleBinding for admin-ui-discovery in that namespace (same file).
// Adding a service *inside* an existing namespace is fully automatic.
// Unknown namespaces fall back to 'platform' so they are never silently hidden.
const NS_GROUP: Record<string, ServiceGroup> = {
  accounts:           'core',
  payments:           'payments',
  compliance:         'compliance',
  identity:           'identity',
  'open-banking':     'open-banking',
  platform:           'platform',
  ledger:             'core',
  balances:           'core',
  audit:              'compliance',
  sanctions:          'compliance',
  'security-scanner': 'platform',
  // Services that live in their own namespace (ADR-0051 three-step checklist):
  party:              'identity',    // party-service (ADR-0055)
  sca:                'identity',    // sca-service (ADR-0021)
  statements:         'compliance',  // statement-service
  onboarding:         'compliance',  // onboarding-service (ADR-0069)
  kyc:                'compliance',  // kyc-service (ADR-0068)
  'customer-edge':    'platform',    // customer-facing edge (ADR-0065)
  notifications:      'platform',    // notification-service (T2 scale-to-zero, ADR-0057)
  // Namespaces added after initial ADR-0051 rollout (each service in its own ns):
  aml:                'compliance',  // aml-service
  consent:            'open-banking', // consent-service (PSD2)
  dispute:            'compliance',  // dispute-service
  fraud:              'compliance',  // fraud-service
  fx:                 'payments',    // fx-service
  interest:           'payments',    // interest-service
  pid:                'identity',    // pid-service (EUDI wallet, ADR-0094)
  psd2:               'open-banking', // psd2-service (Berlin Group)
  documents:          'platform',    // document-service (ADR-0161/0162)
  engagement:         'platform',    // engagement-service (ADR-0220 in-app surfaces)
  referral:           'platform',    // referral-service (ADR-0266 MGM attribution)
  incentive:          'platform',    // incentive/promo service (ADR-0266)
}

export function inCluster(): boolean {
  return existsSync(TOKEN_PATH) && Boolean(process.env.KUBERNETES_SERVICE_HOST)
}

// Infra workloads can legitimately live in a domain namespace (e.g. the `redis`
// idempotency cache sits in `accounts`), but they are NOT OpenBank business
// services and must not pollute the service inventory / health counts — that is
// what made the dashboard read "x/27" and Tech Inventory "2/3" (redis has no
// /api/v1/info stack). Infra belongs in /api/infra/status, not here. Denylist
// (not an allowlist) so a real business service that doesn't match a naming
// convention is never silently hidden.
const INFRA_WORKLOADS = new Set(['redis', 'valkey', 'memcached', 'rabbitmq'])
function isBusinessService(name: string): boolean {
  if (INFRA_WORKLOADS.has(name)) return false
  if (/-db($|-)/.test(name) || name.endsWith('-cache') || name.endsWith('-operator')) return false
  return true
}

function namespaces(): string[] {
  return (
    process.env.OPENBANK_NAMESPACES ??
    'accounts,payments,compliance,identity,open-banking,platform'
  )
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

function k8sGet<T>(path: string): Promise<T> {
  const token = readFileSync(TOKEN_PATH, 'utf8')
  const ca = readFileSync(CA_PATH)
  const host = process.env.KUBERNETES_SERVICE_HOST as string
  const port =
    process.env.KUBERNETES_SERVICE_PORT_HTTPS ??
    process.env.KUBERNETES_SERVICE_PORT ??
    '443'

  return new Promise<T>((resolve, reject) => {
    // The projected ServiceAccount token is meant to travel exactly here: it
    // authenticates this in-cluster request to the Kubernetes API server
    // itself (host/port from the standard KUBERNETES_SERVICE_* env, verified
    // against the same pod's CA bundle) — not a leak, the intended auth flow.
    // codeql[js/file-access-to-http]
    const req = https.request(
      {
        host,
        port,
        path,
        method: 'GET',
        ca,
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
        timeout: 5000,
      },
      (res) => {
        const chunks: Buffer[] = []
        res.on('data', (c) => chunks.push(c as Buffer))
        res.on('end', () => {
          const body = Buffer.concat(chunks).toString('utf8')
          const code = res.statusCode ?? 500
          if (code >= 400) {
            reject(new Error(`k8s ${code}: ${body.slice(0, 200)}`))
            return
          }
          try {
            resolve(JSON.parse(body) as T)
          } catch (e) {
            reject(e instanceof Error ? e : new Error(String(e)))
          }
        })
      },
    )
    req.on('error', reject)
    req.on('timeout', () => req.destroy(new Error('k8s api timeout')))
    req.end()
  })
}

interface WorkloadList {
  items: Array<{
    metadata: { name: string; namespace: string; labels?: Record<string, string> }
    spec?: {
      replicas?: number
      template?: { spec?: { containers?: Array<{ ports?: Array<{ name?: string; containerPort: number }> }> } }
    }
    status?: { replicas?: number; readyReplicas?: number; availableReplicas?: number }
  }>
}

type WorkloadItem = WorkloadList['items'][number]

// The business HTTP port a sibling pod can reach via the Service. Prefer the
// container port named "http"; fall back to the 81xx fleet convention; else the
// first declared port.
function businessPort(d: WorkloadItem): number {
  const ports = d.spec?.template?.spec?.containers?.[0]?.ports ?? []
  const named = ports.find((p) => p.name === 'http')
  const fleet = ports.find((p) => p.containerPort >= 8100 && p.containerPort <= 8199)
  return named?.containerPort ?? fleet?.containerPort ?? ports[0]?.containerPort ?? 8080
}

/**
 * Discover OpenBank services from the cluster. Returns null off-cluster (no SA
 * token) or on any API error, so the caller can fall back to the static list.
 *
 * Queries BOTH Deployments and Argo Rollouts — the fleet uses Rollouts for
 * canary/blue-green on money-path services (ADR-0075). Deployments-only discovery
 * misses account-service, ledger-service, balance-service, sepa-payment, etc.
 */
export async function discoverServices(): Promise<DiscoveredService[] | null> {
  if (!inCluster()) return null
  try {
    const ns = namespaces()
    // Per-namespace list calls, NOT a cluster-scoped collection request. The SA
    // holds list/watch only inside the OpenBank domain namespaces via
    // per-namespace RoleBindings (ADR-0051 least-privilege). A namespace we lack
    // access to fails soft to [] so a single bad namespace can't blank the view.
    const [deploymentItems, rolloutItems] = await Promise.all([
      Promise.all(
        ns.map((n) =>
          k8sGet<WorkloadList>(`/apis/apps/v1/namespaces/${n}/deployments?limit=500`)
            .then((list) => list.items)
            .catch(() => [] as WorkloadList['items']),
        ),
      ).then((r) => r.flat()),
      Promise.all(
        ns.map((n) =>
          k8sGet<WorkloadList>(`/apis/argoproj.io/v1alpha1/namespaces/${n}/rollouts?limit=500`)
            .then((list) => list.items)
            .catch(() => [] as WorkloadList['items']),
        ),
      ).then((r) => r.flat()),
    ])

    // Merge: Rollouts take precedence when the same name exists in both lists
    // (Argo Rollouts creates a Deployment stub that we should not double-count).
    const rolloutNames = new Set(rolloutItems.map((r) => `${r.metadata.namespace}/${r.metadata.name}`))
    const allItems = [
      ...deploymentItems.filter(
        (d) => !rolloutNames.has(`${d.metadata.namespace}/${d.metadata.name}`),
      ),
      ...rolloutItems,
    ]

    return allItems
      .filter((d) => d.metadata.name !== 'admin-ui')
      .filter((d) => isBusinessService(d.metadata.name))
      .map((d) => {
        const replicas = d.status?.replicas ?? d.spec?.replicas ?? 0
        const readyReplicas = d.status?.readyReplicas ?? 0
        const desired = d.spec?.replicas ?? d.status?.replicas ?? 0
        return {
          name: d.metadata.name,
          namespace: d.metadata.namespace,
          port: businessPort(d),
          group: NS_GROUP[d.metadata.namespace] ?? 'platform',
          ready: readyReplicas > 0 && readyReplicas >= replicas,
          replicas,
          readyReplicas,
          scaledToZero: desired === 0 && readyReplicas === 0,
        }
      })
      .sort((a, b) => a.name.localeCompare(b.name))
  } catch {
    return null
  }
}

// Shared in-cluster base-URL resolver, cached briefly so callers (docs proxy,
// any future server-side per-service fetch) don't hit the K8s API on every
// request while still picking up new services within the TTL. Mirrors the
// resolution the BFF proxy uses, so server-side fetches reach the real Service
// DNS (`<name>.<namespace>.svc:<port>`) instead of a compose hostname/localhost
// that doesn't resolve inside the pod.
let _baseUrlCache: { at: number; map: Record<string, { namespace: string; port: number }> } | null = null
const BASEURL_TTL_MS = 30_000

/**
 * In-cluster base URL for a Kubernetes Deployment/Service name, e.g.
 * `account-service` → `http://account-service.accounts.svc:8100`. Resolved via
 * the ADR-0051 discovery feed. Returns null off-cluster (no SA token) or when
 * the service isn't currently discovered (undeployed), so callers can degrade to
 * a graceful "not deployed" state instead of hanging on an unresolvable host.
 */
export async function resolveInClusterBaseUrl(k8sName: string): Promise<string | null> {
  if (!inCluster()) return null
  const now = Date.now()
  if (!_baseUrlCache || now - _baseUrlCache.at > BASEURL_TTL_MS) {
    const discovered = await discoverServices()
    if (discovered) {
      const map: Record<string, { namespace: string; port: number }> = {}
      for (const d of discovered) map[d.name] = { namespace: d.namespace, port: d.port }
      _baseUrlCache = { at: now, map }
    }
  }
  const hit = _baseUrlCache?.map[k8sName]
  return hit ? `http://${k8sName}.${hit.namespace}.svc:${hit.port}` : null
}

/** "account-service" → "Account Service" for display. */
export function prettyLabel(name: string): string {
  return name
    .split('-')
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}
