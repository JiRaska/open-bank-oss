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

import { discoveryServiceKey, discoveredBaseUrl } from '@/lib/treasury-company'
import { readFileSync, existsSync } from 'node:fs'
import https from 'node:https'
import { getRegistry } from '@/lib/services/fleet'
import { k8sNameOf } from '@/lib/services/registry'

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
  /**
   * Image reference of the workload's first container, read from the pod TEMPLATE — the
   * desired build, known without contacting any pod (and without waking a scaled-to-zero one).
   */
  image?: string
}

export type DiscoveryStatus = 'complete' | 'partial' | 'unavailable' | 'static'

export interface ServiceDiscoverySnapshot {
  status: DiscoveryStatus
  services: DiscoveredService[]
}

// Namespace → UI group. Adding a whole new domain namespace requires THREE steps
// (ADR-0051, "new namespace checklist") — no code deploy needed after this:
//   1. Add it here (determines the group chip in System Health).
//   2. Add it to OPENBANK_NAMESPACES in gitops/components/admin-ui/admin-ui.yaml.
//   3. Add a RoleBinding for admin-ui-discovery in that namespace (same file).
// Adding a service *inside* an existing namespace is fully automatic.
// Unknown namespaces fall back to 'platform' so they are never silently hidden.
const NS_GROUP: Record<string, ServiceGroup> = {
  'treasury-pension-co': 'core',
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
  kyb:                'compliance',  // kyb-service (ADR-0284 legal-entity onboarding)
  communication:      'platform',    // communication-service (ADR-0285 Communication Studio)
  context:            'compliance',  // context-service (ADR-0303 investigation graph)
  pricing:            'platform',    // pricing-service and pricing-console
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
  metadata?: { continue?: string }
  items: Array<{
    metadata: { name: string; namespace: string; labels?: Record<string, string> }
    spec?: {
      replicas?: number
      template?: { spec?: { containers?: Array<{ image?: string; ports?: Array<{ name?: string; containerPort: number }> }> } }
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
 * Collect a complete OpenBank inventory from the cluster. A failed namespaced
 * list makes the inventory incomplete; partial results must not be interpreted
 * as an authoritative list of deployed services.
 *
 * Queries BOTH Deployments and Argo Rollouts — the fleet uses Rollouts for
 * canary/blue-green on money-path services (ADR-0075). Deployments-only discovery
 * misses account-service, ledger-service, balance-service, sepa-payment, etc.
 */
export async function collectServiceDiscovery(
  listWorkloads: (path: string) => Promise<WorkloadList>,
  targetNamespaces: string[] = namespaces(),
): Promise<ServiceDiscoverySnapshot> {
  if (targetNamespaces.length === 0) return { status: 'unavailable', services: [] }

  const list = async (path: string) => {
    try {
      const response = await listWorkloads(path)
      // A 500-item page with a continuation token is not a complete namespace
      // inventory. Keep its observed rows, but mark the overall read partial.
      return { items: response.items, ok: !response.metadata?.continue }
    } catch {
      return { items: [] as WorkloadList['items'], ok: false }
    }
  }

  const [deploymentResults, rolloutResults] = await Promise.all([
    Promise.all(targetNamespaces.map((n) => list(`/apis/apps/v1/namespaces/${n}/deployments?limit=500`))),
    Promise.all(targetNamespaces.map((n) => list(`/apis/argoproj.io/v1alpha1/namespaces/${n}/rollouts?limit=500`))),
  ])
  const results = [...deploymentResults, ...rolloutResults]
  const successfulLists = results.filter((result) => result.ok).length
  const status: DiscoveryStatus = successfulLists === results.length
    ? 'complete'
    : successfulLists === 0 ? 'unavailable' : 'partial'

  const deploymentItems = deploymentResults.flatMap((result) => result.items)
  const rolloutItems = rolloutResults.flatMap((result) => result.items)

  // Merge: Rollouts take precedence when the same name exists in both lists
  // (Argo Rollouts creates a Deployment stub that should not be double-counted).
  const rolloutNames = new Set(rolloutItems.map((r) => `${r.metadata.namespace}/${r.metadata.name}`))
  const allItems = [
    ...deploymentItems.filter((d) => !rolloutNames.has(`${d.metadata.namespace}/${d.metadata.name}`)),
    ...rolloutItems,
  ]

  const services = allItems
    .filter((d) => d.metadata.name !== 'admin-ui')
    .filter((d) => isBusinessService(d.metadata.name))
    .map((d) => {
      const replicas = d.status?.replicas ?? d.spec?.replicas ?? 0
      const readyReplicas = d.status?.readyReplicas ?? 0
      const desired = d.spec?.replicas ?? d.status?.replicas ?? 0
      return {
        name: discoveryServiceKey(d.metadata.name, d.metadata.namespace),
        namespace: d.metadata.namespace,
        port: businessPort(d),
        group: NS_GROUP[d.metadata.namespace] ?? 'platform',
        ready: readyReplicas > 0 && readyReplicas >= replicas,
        replicas,
        readyReplicas,
        scaledToZero: desired === 0 && readyReplicas === 0,
        image: d.spec?.template?.spec?.containers?.[0]?.image,
      }
    })
    .sort((a, b) => a.name.localeCompare(b.name))

  return { status, services }
}

/** Return collection state as well as the result so operational consumers can
 * distinguish an empty cluster from a failed or partial Kubernetes read. */
export async function discoverServicesSnapshot(): Promise<ServiceDiscoverySnapshot> {
  if (!inCluster()) return { status: 'static', services: [] }
  try {
    return await collectServiceDiscovery((path) => k8sGet<WorkloadList>(path))
  } catch {
    return { status: 'unavailable', services: [] }
  }
}

/** Compatibility helper for callers that only accept a complete inventory. */
export async function discoverServices(): Promise<DiscoveredService[] | null> {
  const snapshot = await discoverServicesSnapshot()
  return snapshot.status === 'complete' ? snapshot.services : null
}

// Shared in-cluster base-URL resolver, cached briefly so callers (docs proxy,
// any future server-side per-service fetch) don't hit the K8s API on every
// request while still picking up new services within the TTL. Mirrors the
// resolution the BFF proxy uses, so server-side fetches reach the real Service
// DNS (`<name>.<namespace>.svc:<port>`) instead of a compose hostname/localhost
// that doesn't resolve inside the pod.
export type InClusterServiceResolution =
  | { state: 'found'; service: DiscoveredService; baseUrl: string; coverage: 'complete' | 'partial' }
  | { state: 'not_found'; coverage: 'complete' }
  | { state: 'unknown'; coverage: 'complete' | 'partial' | 'unavailable' }
  | { state: 'off_cluster'; coverage: 'static' }

let _discoverySnapshotCache: { at: number; snapshot: ServiceDiscoverySnapshot } | null = null
const BASEURL_TTL_MS = 30_000

async function cachedDiscoverySnapshot(): Promise<ServiceDiscoverySnapshot> {
  const now = Date.now()
  if (_discoverySnapshotCache && now - _discoverySnapshotCache.at <= BASEURL_TTL_MS) {
    return _discoverySnapshotCache.snapshot
  }
  const snapshot = await discoverServicesSnapshot()
  _discoverySnapshotCache = { at: now, snapshot }
  return snapshot
}

/** Resolve a workload without turning an incomplete Kubernetes read into a negative.
 * A known positive from a partial list is usable; absence is authoritative only in a
 * complete snapshot. Duplicate workload names are ambiguous and therefore unknown.
 */
export function resolveServiceInSnapshot(
  snapshot: ServiceDiscoverySnapshot,
  k8sName: string,
  preferredNamespace?: string,
): InClusterServiceResolution {
  if (snapshot.status === 'static') return { state: 'off_cluster', coverage: 'static' }
  if (snapshot.status === 'unavailable') return { state: 'unknown', coverage: 'unavailable' }
  const sameName = snapshot.services.filter((service) => service.name === k8sName)
  const matches = preferredNamespace
    ? sameName.filter((service) => service.namespace === preferredNamespace)
    : sameName
  if (matches.length > 1) return { state: 'unknown', coverage: snapshot.status }
  if (matches.length === 1) {
    const service = matches[0]
    return {
      state: 'found',
      service,
      baseUrl: discoveredBaseUrl(service.name, service.namespace, service.port),
      coverage: snapshot.status,
    }
  }
  return snapshot.status === 'complete'
    ? { state: 'not_found', coverage: 'complete' }
    : { state: 'unknown', coverage: 'partial' }
}

export async function resolveInClusterService(k8sName: string): Promise<InClusterServiceResolution> {
  if (!inCluster()) return { state: 'off_cluster', coverage: 'static' }
  // This preference is sourced only from the closed, code-derived service registry.
  // No request path or caller-supplied namespace can influence upstream selection.
  const preferredNamespace = getRegistry()
    .find((service) => k8sNameOf(service) === k8sName)?.k8sNamespace
  return resolveServiceInSnapshot(await cachedDiscoverySnapshot(), k8sName, preferredNamespace)
}

/** Compatibility URL helper. Null means no usable positive observation; callers
 * that need to distinguish absence from incomplete discovery must use the typed resolver. */
export async function resolveInClusterBaseUrl(k8sName: string): Promise<string | null> {
  const result = await resolveInClusterService(k8sName)
  return result.state === 'found' ? result.baseUrl : null
}

/** "account-service" → "Account Service" for display. */
export function prettyLabel(name: string): string {
  return name
    .split('-')
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}
