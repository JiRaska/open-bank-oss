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
import { readAdminUiContentSnapshot, contentSourceMetadata } from '@/lib/evidence/contentSource'
import { platformVersions, type PlatformVersions, type EksLifecycle } from '@/lib/platform-versions'

export const PROM_TIMEOUT_MS = 3000
export const LIVE_CACHE_MS = 60_000
// A running-container image is accepted only when kube-state-metrics sampled it recently.
// This bounds the window in which a disconnected/stale scrape could masquerade as current.
export const IMAGE_SAMPLE_MAX_AGE_MS = 5 * 60_000
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
  clickhouse: /(?:^|\/)clickhouse\/clickhouse-server:(\d+(?:\.\d+)+)(?=$|[-_@])/,
  postgres: /cloudnative-pg\/postgresql:(\d+\.\d+)(?![\d.])/,
  valkey: /valkey\/valkey:(\d+\.\d+\.\d+)/,
  // Strimzi publishes one image per Kafka version: quay.io/strimzi/kafka:<operator>-kafka-X.Y.Z
  kafka: /strimzi\/kafka:[^\s@]*-kafka-(\d+\.\d+\.\d+)/,
  strimziOperator: /strimzi\/operator:(\d+\.\d+\.\d+)/,
  // The scanner registry identifies the component as schema-registry while Prometheus labels images.
  apicurio: /apicurio\/apicurio-registry[a-z-]*:(\d+\.\d+\.\d+)/,
  'schema-registry': /apicurio\/apicurio-registry[a-z-]*:(\d+\.\d+\.\d+)/,
  'kafka-ui': /kafbat\/kafka-ui:v?(\d+\.\d+\.\d+)/,
  openbao: /openbao\/openbao:(\d+\.\d+\.\d+)/,
  prometheus: /prometheus\/prometheus:v?(\d+\.\d+\.\d+)/,
  tempo: /grafana\/tempo:(\d+\.\d+\.\d+)/,
  alloy: /(?:^|\/)alloy:v?(\d+\.\d+\.\d+)/,
  pyroscope: /grafana\/pyroscope:v?(\d+\.\d+\.\d+)/,
  glitchtip: /glitchtip(?:\/glitchtip)?:v?(\d+\.\d+\.\d+)/,
  alertmanager: /prometheus\/alertmanager:v?(\d+\.\d+\.\d+)/,
  'otel-collector': /otel\/opentelemetry-collector-contrib:v?(\d+\.\d+\.\d+)/,
  pyrra: /pyrra-dev\/pyrra:v?(\d+\.\d+\.\d+)/,
  goalert: /goalert\/goalert:v?(\d+\.\d+\.\d+)/,
  ntfy: /binwiederhier\/ntfy:v?(\d+\.\d+\.\d+)/,
  temporal: /temporalio\/server:v?(\d+\.\d+\.\d+)/,
  keda: /kedacore\/keda(?:-operator)?:v?(\d+\.\d+\.\d+)/,
  argocd: /argoproj\/argocd:v?(\d+\.\d+\.\d+)/,
  kyverno: /kyverno\/(?:kyverno|background-controller|cleanup-controller|reports-controller|admission-controller):v?(\d+\.\d+\.\d+)/,
  'cert-manager': /jetstack\/cert-manager-(?:controller|webhook|cainjector|startupapicheck):v?(\d+\.\d+\.\d+)/,
  karpenter: /karpenter\/controller:v?(\d+\.\d+\.\d+)/,
  loki: /(?:^|\/)grafana\/loki:(\d+\.\d+\.\d+)/,
  keycloak: /keycloak\/keycloak:(\d+\.\d+\.\d+)/,
  grafana: /(?:^|\/)grafana\/grafana:(\d+\.\d+\.\d+)/,
}

// Components already surfaced in the platform-version page. Lifecycle-only image
// patterns are deliberately excluded from that page's declared-vs-live table.
const PLATFORM_IMAGE_COMPONENTS = ['postgres', 'valkey', 'apicurio', 'kafka', 'strimziOperator', 'loki', 'keycloak', 'grafana'] as const

// Repository identity fallbacks allow a digest-pinned ref to remain visible when
// its tag does not encode a parseable release version. These entries come from
// checked-in GitOps image refs/chart ownership, not display-name-only guesses.
const IMAGE_REPOSITORIES: Record<string, RegExp> = {
  clickhouse: /(?:^|\/)clickhouse\/clickhouse-server(?=[:@])/,
  postgres: /(?:^|\/)cloudnative-pg\/postgresql(?=[:@])/,
  valkey: /(?:^|\/)valkey\/valkey(?=[:@])/,
  kafka: /(?:^|\/)strimzi\/kafka(?=[:@])/,
  strimziOperator: /(?:^|\/)strimzi\/operator(?=[:@])/,
  apicurio: /(?:^|\/)apicurio\/apicurio-registry[a-z-]*(?=[:@])/,
  'schema-registry': /(?:^|\/)apicurio\/apicurio-registry[a-z-]*(?=[:@])/,
  'kafka-ui': /(?:^|\/)kafbat\/kafka-ui(?=[:@])/,
  openbao: /(?:^|\/)openbao\/openbao(?=[:@])/,
  prometheus: /(?:^|\/)prometheus\/prometheus(?=[:@])/,
  tempo: /(?:^|\/)grafana\/tempo(?=[:@])/,
  alloy: /(?:^|\/)alloy(?=[:@])/,
  pyroscope: /(?:^|\/)grafana\/pyroscope(?=[:@])/,
  glitchtip: /(?:^|\/)glitchtip(?:\/glitchtip)?(?=[:@])/,
  alertmanager: /(?:^|\/)prometheus\/alertmanager(?=[:@])/,
  'otel-collector': /(?:^|\/)otel\/opentelemetry-collector-contrib(?=[:@])/,
  pyrra: /(?:^|\/)pyrra-dev\/pyrra(?=[:@])/,
  goalert: /(?:^|\/)goalert\/goalert(?=[:@])/,
  ntfy: /(?:^|\/)binwiederhier\/ntfy(?=[:@])/,
  temporal: /(?:^|\/)temporalio\/server(?=[:@])/,
  keda: /(?:^|\/)kedacore\/keda(?:-operator)?(?=[:@])/,
  argocd: /(?:^|\/)argoproj\/argocd(?=[:@])/,
  kyverno: /(?:^|\/)kyverno\/(?:kyverno|background-controller|cleanup-controller|reports-controller|admission-controller)(?=[:@])/,
  'cert-manager': /(?:^|\/)jetstack\/cert-manager-(?:controller|webhook|cainjector|startupapicheck)(?=[:@])/,
  karpenter: /(?:^|\/)karpenter\/controller(?=[:@])/,
  loki: /(?:^|\/)grafana\/loki(?=[:@])/,
  keycloak: /(?:^|\/)keycloak\/keycloak(?=[:@])/,
  grafana: /(?:^|\/)grafana\/grafana(?=[:@])/,
}

export function parseImageVersion(component: string, image: string): string | null {
  const re = IMAGE_PATTERNS[component]
  const match = re?.exec(image)
  if (match?.[1]) return match[1]
  if (IMAGE_REPOSITORIES[component]?.test(image)) {
    const digest = /@sha256:([a-f\d]{64})(?:$|\s)/i.exec(image)?.[1]
    if (digest) return `sha256:${digest}`
  }
  return null
}

/** Aggregate running-container image rows into distinct running versions per component. */
export interface LiveImageVersion extends LiveVersion { observedAt: string; source?: 'prometheus-runtime-build-info' }

export function aggregateImages(rows: { image: string; container?: string; namespace?: string; count: number; observedAt: string }[]): Record<string, LiveImageVersion[]> {
  const out: Record<string, Map<string, { count: number; observedAt: string }>> = {}
  for (const { image, container, namespace, count, observedAt } of rows) {
    for (const component of Object.keys(IMAGE_PATTERNS)) {
      // Role and workload identity are part of the image evidence: Kafka exporters can carry
      // broker-like image tags, and the analytics card must not absorb other ClickHouse instances.
      if (component === 'kafka' && container !== 'kafka') continue
      // The infrastructure card represents the analytics warehouse. Other ClickHouse
      // instances (for example, an application-bundled database) are separate components.
      if (component === 'clickhouse' && (container !== 'clickhouse' || namespace !== 'analytics')) continue
      const v = parseImageVersion(component, image)
      if (v) {
        const m = (out[component] ??= new Map())
        const previous = m.get(v)
        m.set(v, {
          count: (previous?.count ?? 0) + count,
          observedAt: previous && previous.observedAt > observedAt ? previous.observedAt : observedAt,
        })
      }
    }
  }
  return Object.fromEntries(
    Object.entries(out).map(([k, m]) => [k, [...m].map(([version, sample]) => ({ version, ...sample })).sort((a, b) => b.count - a.count)]),
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
  // Control plane (apiserver) and node kubelets are different subjects: a mixed query would
  // blur an EKS minor upgrade that has not rolled onto the nodes yet.
  apiserver: 'count by (git_version) (kubernetes_build_info{job="apiserver"})',
  kubelet: 'count by (git_version) (kubernetes_build_info{job="kubelet"})',
  nodeTypes: 'count by (label_node_kubernetes_io_instance_type, label_karpenter_sh_capacity_type) (kube_node_labels)',
  nodesReady: 'count(kube_node_status_condition{condition="Ready",status="true"} == 1)',
  nodesTotal: 'count(kube_node_info)',
  // One sample per container, with its scrape timestamp as the value. Restrict to the
  // kube-state-metrics running condition; stopped/terminating pod specs are not runtime evidence.
  images: 'timestamp(kube_pod_container_info and on (namespace, pod, container) (kube_pod_container_status_running == 1))',
  karpenterBuild: 'timestamp(karpenter_build_info{namespace="kube-system",container="controller"})',
  podsRunning: 'sum by (namespace) (kube_pod_status_phase{phase="Running"})',
  daemonSetsReady: 'sum by (namespace, daemonset) (kube_daemonset_status_number_ready)',
} as const

export interface LiveSnapshot {
  available: boolean
  fetchedAt: string
  error: string | null
  /** Control plane (apiserver job). */
  kubernetes: LiveVersion[] | null
  /** Node kubelets (kubelet job). */
  kubelets: LiveVersion[] | null
  podsRunningByNamespace: Record<string, number> | null
  daemonSetsReady: Record<string, number> | null
  nodes: NodeSummary | null
  components: Record<string, LiveImageVersion[]>
}

export async function fetchLiveSnapshot(fetchImpl: FetchLike = fetch, now: () => Date = () => new Date()): Promise<LiveSnapshot> {
  const settled = await Promise.allSettled(Object.values(QUERIES).map(q => promQuery(q, fetchImpl)))
  const [apiserver, kubelet, nodeTypes, ready, total, images, karpenterBuild, pods, daemonSets] = settled
  const errors = settled.flatMap(s => (s.status === 'rejected' ? [String(s.reason)] : []))
  const rows = (s: PromiseSettledResult<PromRow[]>): PromRow[] | null => (s.status === 'fulfilled' ? s.value : null)

  const versionsOf = (r: PromiseSettledResult<PromRow[]>): LiveVersion[] | null => {
    const m = new Map<string, number>()
    for (const row of rows(r) ?? []) {
      const p = parseKubeGitVersion(row.metric.git_version ?? '')
      if (p) m.set(p.full, (m.get(p.full) ?? 0) + row.value)
    }
    return m.size > 0 ? [...m].map(([version, count]) => ({ version, count })) : null
  }
  const kubernetes = versionsOf(apiserver)
  const kubelets = versionsOf(kubelet)
  const toMap = (r: PromiseSettledResult<PromRow[]>, key: (m: Record<string, string>) => string): Record<string, number> | null => {
    const rs = rows(r)
    return rs && rs.length > 0 ? Object.fromEntries(rs.map(x => [key(x.metric), x.value])) : null
  }
  const podsRunningByNamespace = toMap(pods, m => m.namespace)
  const daemonSetsReady = toMap(daemonSets, m => `${m.namespace}/${m.daemonset}`)

  const fetchedAt = now()
  const imageRows = rows(images)
  const freshImageRows = (imageRows ?? [])
    .filter(r => r.metric.image && Number.isFinite(r.value))
    .map(r => ({ image: r.metric.image, container: r.metric.container, namespace: r.metric.namespace, sampledAtMs: r.value * 1000 }))
    .filter(r => {
      const ageMs = fetchedAt.getTime() - r.sampledAtMs
      return ageMs >= -30_000 && ageMs <= IMAGE_SAMPLE_MAX_AGE_MS
    })
    .map(r => ({ image: r.image, container: r.container, namespace: r.namespace, count: 1, observedAt: new Date(r.sampledAtMs).toISOString() }))
  const components = aggregateImages(freshImageRows)
  // The controller reports its own runtime build version. kube-state-metrics may expose
  // only a platform digest, which cannot safely be mapped back to a desired image tag.
  const karpenterVersions = (rows(karpenterBuild) ?? []).filter(r => {
    const ageMs = fetchedAt.getTime() - r.value * 1000
    return r.metric.namespace === 'kube-system' && r.metric.container === 'controller'
      && /^v?\d+\.\d+\.\d+$/.test(r.metric.version ?? '')
      && Number.isFinite(r.value) && ageMs >= -30_000 && ageMs <= IMAGE_SAMPLE_MAX_AGE_MS
  }).map(r => ({ version: r.metric.version.replace(/^v/, ''), count: 1, observedAt: new Date(r.value * 1000).toISOString() }))
  if (karpenterVersions.length > 0) {
    const versions = new Map<string, LiveImageVersion>()
    for (const sample of karpenterVersions) {
      const previous = versions.get(sample.version)
      versions.set(sample.version, {
        ...sample,
        source: 'prometheus-runtime-build-info',
        count: (previous?.count ?? 0) + 1,
        observedAt: previous && previous.observedAt > sample.observedAt ? previous.observedAt : sample.observedAt,
      })
    }
    components.karpenter = [...versions.values()].sort((a, b) => b.count - a.count)
  }

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
  if (errors.length > 0) console.error('[live-platform-versions] prometheus query failures:', errors.join('; '))
  const available = freshImageRows.length > 0 || karpenterVersions.length > 0 || kubernetes !== null || kubelets !== null
  return {
    available,
    fetchedAt: fetchedAt.toISOString(),
    error: available ? null : 'prometheus_unavailable',
    kubernetes,
    kubelets,
    podsRunningByNamespace,
    daemonSetsReady,
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

export function buildView(live: LiveSnapshot, eksSource: PlatformView['eksLifecycleSource'], pv: PlatformVersions | null = platformVersions): PlatformView {
  const declaredFor: Record<string, { v: string | null; src: string | null }> = {
    kubernetes: { v: pv?.kubernetesVersion ?? null, src: pv?.sources.kubernetesVersion ?? null },
    loki: { v: pv?.loki.appVersion ?? null, src: pv?.sources.loki ?? null },
  }
  for (const key of ['postgres', 'valkey', 'apicurio', 'kafka', 'strimziOperator'] as const) {
    const c = pv?.components[key]
    declaredFor[key] = { v: c?.version ?? null, src: c?.source ?? null }
  }
  const items: Record<string, VersionItem> = {}
  const keys = ['kubernetes', 'kubelet', ...PLATFORM_IMAGE_COMPONENTS]
  for (const key of keys) {
    if (key === 'kubelet') {
      // Kubelets are compared with the LIVE control plane when known (a skewed upgrade is drift),
      // else with the declared minor.
      const cp = live.available ? live.kubernetes : null
      const ref = cp ? minor(cp[0].version) : (declaredFor.kubernetes.v ?? null)
      const kv = live.available ? live.kubelets : null
      items.kubelet = {
        key,
        declared: ref,
        declaredSource: cp ? 'control plane (live apiserver)' : declaredFor.kubernetes.src,
        live: kv,
        status: compareVersions(ref, kv, minor),
      }
      continue
    }
    const observed = live.available ? (key === 'kubernetes' ? live.kubernetes : (live.components[key] ?? null)) : null
    // A digest is useful workload identity but cannot be compared with a declared semver tag.
    // The lifecycle route exposes that digest directly; this declared-vs-version view must not
    // turn an incomparable identity into a false drift warning.
    const liveVersions = observed?.some(v => v.version.startsWith('sha256:')) ? null : observed
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
    podsRunningByNamespace: live.available ? live.podsRunningByNamespace : null,
    daemonSetsReady: live.available ? live.daemonSetsReady : null,
    inventory: pv?.inventory ?? null,
    declaredNodeGroup: pv?.nodeGroup ?? null,
    eksLifecycleSource: eksSource,
  }
}

export async function getPlatformView(fetchImpl: FetchLike = fetch): Promise<PlatformView> {
  const [live, eol, content] = await Promise.all([getLiveSnapshot(fetchImpl), getEksLifecycle(fetchImpl), readAdminUiContentSnapshot()])
  const declared = content.snapshot?.content.platformVersions
  const provenance = declared ? content : { status: 'unavailable' as const, snapshot: null, provenance: null, error: content.error ?? 'Published content has no platform declarations' }
  return {
    ...buildView(live, eol?.source ?? 'snapshot', declared ?? platformVersions),
    declarationSource: contentSourceMetadata(provenance, { imageFallbackAvailable: platformVersions !== null }),
  }
}
