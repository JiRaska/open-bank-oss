// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// SERVER-ONLY. Reads what is actually RUNNING from Prometheus (kube-state-metrics + kubelet
// metrics from kube-prometheus-stack) and joins it with the DECLARED versions parsed from the
// infra sources (platform-versions.json). Nothing here knows a version number: image tags are
// parsed with patterns, so nothing needs updating when the platform moves.
import {
  compareVersions,
  type LiveVersion,
  type NodeSummary,
  type PlatformView,
  type VersionItem,
} from '@/lib/platform-view'
import { platformVersions, type EksLifecycle } from '@/lib/platform-versions'

export const PROM_TIMEOUT_MS = 3000
export const LIVE_CACHE_MS = 60_000
export const EOL_CACHE_MS = 24 * 3600_000
export const EOL_NEGATIVE_CACHE_MS = 5 * 60_000
export const EOL_URL = 'https://endoflife.date/api/amazon-eks.json'

type FetchLike = typeof fetch

function prometheusBase(): string {
  if (process.env.SERVICES_HOST === 'container') return 'http://prometheus:9090'
  return process.env.PROMETHEUS_URL ?? 'http://localhost:9090'
}

// ---- pure parsers -----------------------------------------------------------------------------

/** `v1.36.0-eks-abc123` -> { full: '1.36.0', minor: '1.36' }; null if not a Kubernetes git version. */
export function parseKubeGitVersion(gitVersion: string): { full: string; minor: string } | null {
  const m = /^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/.exec(gitVersion.trim())
  return m ? { full: `${m[1]}.${m[2]}.${m[3]}`, minor: `${m[1]}.${m[2]}` } : null
}

/** Image pattern per component. First capture group is the version. */
export const IMAGE_PATTERNS: Record<string, RegExp> = {
  postgres: /cloudnative-pg\/postgresql:(\d+\.\d+)(?![\d.])/,
  valkey: /valkey\/valkey:(\d+\.\d+\.\d+)/,
  // Strimzi publishes one image per Kafka version: quay.io/strimzi/kafka:<operator>-kafka-X.Y.Z
  kafka: /strimzi\/kafka:[^\s@]*-kafka-(\d+\.\d+\.\d+)/,
  strimziOperator: /strimzi\/operator:(\d+\.\d+\.\d+)/,
  apicurio: /apicurio\/apicurio-registry[a-z-]*:(\d+\.\d+\.\d+)/,
  loki: /(?:^|\/)grafana\/loki:(\d+\.\d+\.\d+)/,
  keycloak: /keycloak\/keycloak:(\d+\.\d+\.\d+)/,
  grafana: /(?:^|\/)grafana\/grafana:(\d+\.\d+\.\d+)/,
}

export function parseImageVersion(component: string, image: string): string | null {
  const re = IMAGE_PATTERNS[component]
  return re ? (re.exec(image)?.[1] ?? null) : null
}

/** Aggregate `count by (image)` rows into distinct running versions per component. */
export function aggregateImages(rows: { image: string; count: number }[]): Record<string, LiveVersion[]> {
  const out: Record<string, Map<string, number>> = {}
  for (const { image, count } of rows) {
    for (const component of Object.keys(IMAGE_PATTERNS)) {
      const v = parseImageVersion(component, image)
      if (v) {
        const m = (out[component] ??= new Map())
        m.set(v, (m.get(v) ?? 0) + count)
      }
    }
  }
  return Object.fromEntries(
    Object.entries(out).map(([k, m]) => [k, [...m].map(([version, count]) => ({ version, count })).sort((a, b) => b.count - a.count)]),
  )
}

// ---- Prometheus -------------------------------------------------------------------------------

interface PromRow { metric: Record<string, string>; value: number }

export async function promQuery(query: string, fetchImpl: FetchLike = fetch): Promise<PromRow[]> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), PROM_TIMEOUT_MS)
  try {
    const res = await fetchImpl(`${prometheusBase()}/api/v1/query?query=${encodeURIComponent(query)}`, {
      signal: controller.signal,
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    })
    if (!res.ok) throw new Error(`prometheus responded ${res.status}`)
    const json = await res.json() as { status: string; data?: { result: { metric: Record<string, string>; value: [number, string] }[] } }
    if (json.status !== 'success' || !json.data) throw new Error(`prometheus query status ${json.status}`)
    return json.data.result
      .map(r => ({ metric: r.metric, value: parseFloat(r.value[1]) }))
      .filter(r => !Number.isNaN(r.value))
  } finally {
    clearTimeout(timer)
  }
}

// Metric names are kube-prometheus-stack defaults (kube-state-metrics, kubelet).
export const QUERIES = {
  kubelet: 'count by (git_version) (kubernetes_build_info)',
  nodeTypes: 'count by (label_node_kubernetes_io_instance_type, label_karpenter_sh_capacity_type) (kube_node_labels)',
  nodesReady: 'count(kube_node_status_condition{condition="Ready",status="true"} == 1)',
  nodesTotal: 'count(kube_node_info)',
  images: 'count by (image) (kube_pod_container_info)',
} as const

export interface LiveSnapshot {
  available: boolean
  fetchedAt: string
  error: string | null
  kubernetes: LiveVersion[] | null
  nodes: NodeSummary | null
  components: Record<string, LiveVersion[]>
}

export async function fetchLiveSnapshot(fetchImpl: FetchLike = fetch, now: () => Date = () => new Date()): Promise<LiveSnapshot> {
  const settled = await Promise.allSettled(Object.values(QUERIES).map(q => promQuery(q, fetchImpl)))
  const [kubelet, nodeTypes, ready, total, images] = settled
  const errors = settled.flatMap(s => (s.status === 'rejected' ? [String(s.reason)] : []))
  const rows = (s: PromiseSettledResult<PromRow[]>): PromRow[] | null => (s.status === 'fulfilled' ? s.value : null)

  const kubeRows = rows(kubelet)
  const kubeVersions = new Map<string, number>()
  for (const r of kubeRows ?? []) {
    const p = parseKubeGitVersion(r.metric.git_version ?? '')
    if (p) kubeVersions.set(p.full, (kubeVersions.get(p.full) ?? 0) + r.value)
  }
  const kubernetes = kubeVersions.size > 0 ? [...kubeVersions].map(([version, count]) => ({ version, count })) : null

  const imageRows = rows(images)
  const components = aggregateImages((imageRows ?? []).filter(r => r.metric.image).map(r => ({ image: r.metric.image, count: r.value })))

  const readyRows = rows(ready)
  const totalRows = rows(total)
  const typeRows = rows(nodeTypes)
  const totalN = totalRows && totalRows.length > 0 ? totalRows[0].value : null
  const nodes: NodeSummary | null = totalN === null ? null : {
    total: totalN,
    ready: readyRows && readyRows.length > 0 ? readyRows[0].value : null,
    byType: (typeRows ?? [])
      .filter(r => r.metric.label_node_kubernetes_io_instance_type)
      .map(r => ({
        instanceType: r.metric.label_node_kubernetes_io_instance_type,
        capacityType: r.metric.label_karpenter_sh_capacity_type ?? null,
        count: r.value,
      })),
  }

  // "Available" means Prometheus actually returned running workloads or a version; an empty
  // answer is a missing scrape target, which must read as unavailable, not as "nothing runs".
  const available = (imageRows?.length ?? 0) > 0 || kubernetes !== null
  return {
    available,
    fetchedAt: now().toISOString(),
    error: available ? null : (errors[0] ?? 'prometheus returned no kube-state-metrics series'),
    kubernetes,
    nodes,
    components,
  }
}

let liveCache: { at: number; snapshot: LiveSnapshot } | null = null

export function resetLiveCaches(): void {
  liveCache = null
  eolCache = null
}

export async function getLiveSnapshot(fetchImpl: FetchLike = fetch, nowMs: number = Date.now()): Promise<LiveSnapshot> {
  if (liveCache && nowMs - liveCache.at < LIVE_CACHE_MS) return liveCache.snapshot
  const snapshot = await fetchLiveSnapshot(fetchImpl)
  liveCache = { at: nowMs, snapshot }
  return snapshot
}

// ---- EKS lifecycle (endoflife.date, 24h cache, build-time snapshot fallback) -----------------------

interface EolCycle { cycle: string; releaseDate?: string; eol?: string | boolean; extendedSupport?: string | boolean }
const DATE = /^\d{4}-\d{2}-\d{2}$/

/** endoflife.date -> our lifecycle shape. A cycle without firm dates is taken from the snapshot or dropped. */
export function mapEolToLifecycle(cycles: EolCycle[], snapshot: EksLifecycle | null): EksLifecycle {
  const versions: EksLifecycle['versions'] = {}
  for (const c of cycles) {
    const fallback = snapshot?.versions[c.cycle]
    const release = c.releaseDate && DATE.test(c.releaseDate) ? c.releaseDate : fallback?.eks_release
    const std = typeof c.eol === 'string' && DATE.test(c.eol) ? c.eol : fallback?.end_of_standard_support
    const ext = typeof c.extendedSupport === 'string' && DATE.test(c.extendedSupport) ? c.extendedSupport : fallback?.end_of_extended_support
    if (release && std && ext) versions[c.cycle] = { eks_release: release, end_of_standard_support: std, end_of_extended_support: ext }
  }
  if (Object.keys(versions).length === 0) throw new Error('endoflife.date returned no usable EKS cycles')
  return { _meta: { pricing_note: snapshot?._meta.pricing_note, last_refreshed: new Date().toISOString().slice(0, 10) }, versions }
}

export interface LifecycleResult { lifecycle: EksLifecycle; source: 'live (endoflife.date)' | 'snapshot' }

let eolCache: { at: number; ttl: number; result: LifecycleResult | null } | null = null

export async function getEksLifecycle(fetchImpl: FetchLike = fetch, nowMs: number = Date.now()): Promise<LifecycleResult | null> {
  const snapshot = platformVersions?.eksLifecycle ?? null
  if (eolCache && nowMs - eolCache.at < eolCache.ttl) return eolCache.result
  let result: LifecycleResult | null = snapshot ? { lifecycle: snapshot, source: 'snapshot' } : null
  let ttl = EOL_NEGATIVE_CACHE_MS
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), PROM_TIMEOUT_MS)
  try {
    const res = await fetchImpl(EOL_URL, { signal: controller.signal, headers: { Accept: 'application/json' }, cache: 'no-store' })
    if (!res.ok) throw new Error(`endoflife.date responded ${res.status}`)
    result = { lifecycle: mapEolToLifecycle(await res.json() as EolCycle[], snapshot), source: 'live (endoflife.date)' }
    ttl = EOL_CACHE_MS
  } catch {
    /* fall back to the build-time snapshot, retry after the negative TTL */
  } finally {
    clearTimeout(timer)
  }
  eolCache = { at: nowMs, ttl, result }
  return result
}

// ---- merged view ------------------------------------------------------------------------------

const minor = (v: string): string => v.split('.').slice(0, 2).join('.')

export function buildView(live: LiveSnapshot, eksSource: PlatformView['eksLifecycleSource']): PlatformView {
  const pv = platformVersions
  const declaredFor: Record<string, { v: string | null; src: string | null }> = {
    kubernetes: { v: pv?.kubernetesVersion ?? null, src: pv?.sources.kubernetesVersion ?? null },
    loki: { v: pv?.loki.appVersion ?? null, src: pv?.sources.loki ?? null },
  }
  for (const key of ['postgres', 'valkey', 'apicurio', 'kafka', 'strimziOperator'] as const) {
    const c = pv?.components[key]
    declaredFor[key] = { v: c?.version ?? null, src: c?.source ?? null }
  }
  const items: Record<string, VersionItem> = {}
  const keys = ['kubernetes', ...Object.keys(IMAGE_PATTERNS)]
  for (const key of keys) {
    const liveVersions = live.available ? (key === 'kubernetes' ? live.kubernetes : (live.components[key] ?? null)) : null
    const d = declaredFor[key] ?? { v: null, src: null }
    items[key] = {
      key,
      declared: d.v,
      declaredSource: d.src,
      live: liveVersions,
      status: compareVersions(d.v, liveVersions, key === 'kubernetes' ? minor : undefined),
    }
  }
  return {
    fetchedAt: live.fetchedAt,
    liveAvailable: live.available,
    liveSource: 'live (Prometheus)',
    liveError: live.error,
    items,
    nodes: live.available ? live.nodes : null,
    declaredNodeGroup: pv?.nodeGroup ?? null,
    eksLifecycleSource: eksSource,
  }
}

export async function getPlatformView(fetchImpl: FetchLike = fetch): Promise<PlatformView> {
  const [live, eol] = await Promise.all([getLiveSnapshot(fetchImpl), getEksLifecycle(fetchImpl)])
  return buildView(live, eol?.source ?? 'snapshot')
}
