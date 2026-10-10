// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  QUERIES,
  aggregateImages,
  buildView,
  fetchLiveSnapshot,
  getEksLifecycle,
  getLiveSnapshot,
  mapEolToLifecycle,
  parseImageVersion,
  parseKubeGitVersion,
  resetLiveCaches,
} from '@/lib/live-platform-versions'
import { compareVersions, formatVersion, nodesText, versionText } from '@/lib/platform-view'

type Row = { metric: Record<string, string>; value: [number, string] }
const prom = (rows: Row[]) => new Response(JSON.stringify({ status: 'success', data: { result: rows } }), { status: 200 })
const row = (metric: Record<string, string>, v: number): Row => ({ metric, value: [0, String(v)] })

/** Mock Prometheus keyed on which query string was sent. */
function mockProm(answers: Partial<Record<keyof typeof QUERIES, Row[]>>): typeof fetch {
  return vi.fn(async (input: RequestInfo | URL) => {
    const q = decodeURIComponent(String(input).split('query=')[1])
    const key = (Object.keys(QUERIES) as (keyof typeof QUERIES)[]).find(k => QUERIES[k] === q)
    return prom((key && answers[key]) || [])
  }) as unknown as typeof fetch
}

beforeEach(() => resetLiveCaches())

describe('parsers', () => {
  it('strips the -eks- suffix from a Kubernetes git version', () => {
    expect(parseKubeGitVersion('v1.99.3-eks-abc123')).toEqual({ full: '1.99.3', minor: '1.99' })
    expect(parseKubeGitVersion('v1.99.3')).toEqual({ full: '1.99.3', minor: '1.99' })
    expect(parseKubeGitVersion('garbage')).toBeNull()
  })

  it('parses the Strimzi Kafka image tag', () => {
    expect(parseImageVersion('kafka', 'quay.io/strimzi/kafka:0.77.0-kafka-9.8.7')).toBe('9.8.7')
    expect(parseImageVersion('kafka', 'quay.io/strimzi/operator:0.77.0')).toBeNull()
    expect(parseImageVersion('strimziOperator', 'quay.io/strimzi/operator:0.77.0')).toBe('0.77.0')
  })

  it('parses postgres, valkey, apicurio, loki, keycloak, grafana', () => {
    expect(parseImageVersion('postgres', 'ghcr.io/cloudnative-pg/postgresql:77.3')).toBe('77.3')
    expect(parseImageVersion('postgres', 'ghcr.io/cloudnative-pg/postgresql:77.3-minimal-bookworm')).toBe('77.3')
    expect(parseImageVersion('valkey', 'docker.io/valkey/valkey:7.7.7-alpine')).toBe('7.7.7')
    expect(parseImageVersion('apicurio', 'docker.io/apicurio/apicurio-registry-sql:5.5.5.Final')).toBe('5.5.5')
    expect(parseImageVersion('loki', 'docker.io/grafana/loki:6.6.6')).toBe('6.6.6')
    expect(parseImageVersion('keycloak', 'quay.io/keycloak/keycloak:44.4.4')).toBe('44.4.4')
    expect(parseImageVersion('grafana', 'docker.io/grafana/grafana:33.3.3')).toBe('33.3.3')
  })

  it('aggregates distinct running versions with pod counts', () => {
    const agg = aggregateImages([
      { image: 'ghcr.io/cloudnative-pg/postgresql:77.3', count: 5 },
      { image: 'ghcr.io/cloudnative-pg/postgresql:77.1', count: 1 },
      { image: 'busybox:1', count: 9 },
    ])
    expect(agg.postgres).toEqual([{ version: '77.3', count: 5 }, { version: '77.1', count: 1 }])
    expect(agg.kafka).toBeUndefined()
  })
})

describe('live snapshot from mocked Prometheus', () => {
  const full = mockProm({
    apiserver: [row({ git_version: 'v1.99.0-eks-aaa' }, 2)],
    kubelet: [row({ git_version: 'v1.98.7-eks-bbb' }, 3)],
    podsRunning: [row({ namespace: 'cert-manager' }, 3)],
    daemonSetsReady: [row({ namespace: 'observability', daemonset: 'alloy' }, 7)],
    nodeTypes: [row({ label_node_kubernetes_io_instance_type: 'x9.big', label_karpenter_sh_capacity_type: 'spot' }, 2)],
    nodesReady: [row({}, 2)],
    nodesTotal: [row({}, 3)],
    images: [row({ image: 'quay.io/strimzi/kafka:0.77.0-kafka-9.8.7' }, 3)],
  })

  it('reads versions, nodes (Ready of total) and node types', async () => {
    const s = await fetchLiveSnapshot(full)
    expect(s.available).toBe(true)
    expect(s.kubernetes).toEqual([{ version: '1.99.0', count: 2 }])
    expect(s.kubelets).toEqual([{ version: '1.98.7', count: 3 }])
    expect(s.podsRunningByNamespace).toEqual({ 'cert-manager': 3 })
    expect(s.daemonSetsReady).toEqual({ 'observability/alloy': 7 })
    expect(s.components.kafka).toEqual([{ version: '9.8.7', count: 3 }])
    expect(s.nodes).toEqual({ ready: 2, total: 3, byType: [{ instanceType: 'x9.big', capacityType: 'spot', count: 2 }] })
    expect(nodesText({ nodes: s.nodes, declaredNodeGroup: null } as never)).toContain('2/3 Ready')
  })

  it('empty Prometheus answers read as unavailable, not as "nothing runs"', async () => {
    const s = await fetchLiveSnapshot(mockProm({}))
    expect(s.available).toBe(false)
    expect(s.error).toBeTruthy()
  })

  it('a timeout/abort falls back to unavailable', async () => {
    const hang = vi.fn((_u: unknown, init?: RequestInit) => new Promise((_res, rej) => {
      init?.signal?.addEventListener('abort', () => rej(new Error('aborted')))
    })) as unknown as typeof fetch
    vi.useFakeTimers()
    const pending = fetchLiveSnapshot(hang)
    await vi.advanceTimersByTimeAsync(3100)
    const s = await pending
    vi.useRealTimers()
    expect(s.available).toBe(false)
    expect(s.error).toBe('prometheus_unavailable')
  })

  it('caches for 60s', async () => {
    await getLiveSnapshot(full, 1_000)
    const calls = (full as unknown as ReturnType<typeof vi.fn>).mock.calls.length
    await getLiveSnapshot(full, 30_000)
    expect((full as unknown as ReturnType<typeof vi.fn>).mock.calls.length).toBe(calls)
    await getLiveSnapshot(full, 62_000)
    expect((full as unknown as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(calls)
  })
})

describe('drift detection and formatting', () => {
  it('match / drift / live-unavailable / not-declared', () => {
    expect(compareVersions('18.6', [{ version: '18.6', count: 3 }])).toBe('match')
    expect(compareVersions('18.6', [{ version: '18.6', count: 3 }, { version: '18.1', count: 1 }])).toBe('drift')
    expect(compareVersions('18.6', [{ version: '19.0', count: 1 }])).toBe('drift')
    expect(compareVersions('18.6', null)).toBe('live-unavailable')
    expect(compareVersions('18.6', [])).toBe('live-unavailable')
    expect(compareVersions(null, [{ version: '1', count: 1 }])).toBe('not-declared')
    expect(compareVersions('1.99', [{ version: '1.99.4', count: 1 }], v => v.split('.').slice(0, 2).join('.'))).toBe('match')
  })

  it('shows live as primary with declared alongside, and flags drift', () => {
    const drift = { key: 'k', declared: '1.98', declaredSource: 'x', live: [{ version: '1.99.0', count: 2 }], status: 'drift' as const }
    expect(formatVersion(drift)).toEqual({ primary: '1.99.0', note: 'declared 1.98', drift: true })
    expect(versionText(drift)).toContain('[DRIFT]')
    const down = { ...drift, live: null, status: 'live-unavailable' as const }
    expect(versionText(down)).toBe('1.98 (declared — live unavailable)')
  })

  it('buildView falls back to declared when live is unavailable and flags drift when live differs', async () => {
    const unavailable = buildView(await fetchLiveSnapshot(mockProm({})), 'snapshot')
    expect(unavailable.liveAvailable).toBe(false)
    expect(unavailable.items.kubernetes.status).toBe('live-unavailable')
    expect(unavailable.nodes).toBeNull()

    const drifted = buildView(await fetchLiveSnapshot(mockProm({ apiserver: [row({ git_version: 'v1.1.0-eks-z' }, 1)], images: [row({ image: 'x' }, 1)] })), 'snapshot')
    expect(drifted.items.kubernetes.status).toBe('drift')
  })

  it('control plane comes from the apiserver job only; kubelets are separate and skew is drift', async () => {
    const skew = buildView(await fetchLiveSnapshot(mockProm({
      apiserver: [row({ git_version: 'v1.99.0-eks-a' }, 2)],
      kubelet: [row({ git_version: 'v1.98.7-eks-b' }, 3)],
      images: [row({ image: 'x' }, 1)],
    })), 'snapshot')
    expect(skew.items.kubernetes.live).toEqual([{ version: '1.99.0', count: 2 }])
    expect(skew.items.kubelet.live).toEqual([{ version: '1.98.7', count: 3 }])
    expect(skew.items.kubelet.status).toBe('drift')

    const aligned = buildView(await fetchLiveSnapshot(mockProm({
      apiserver: [row({ git_version: 'v1.99.0-eks-a' }, 2)],
      kubelet: [row({ git_version: 'v1.99.2-eks-b' }, 3)],
      images: [row({ image: 'x' }, 1)],
    })), 'snapshot')
    expect(aligned.items.kubelet.status).toBe('match')
  })

  it('never exposes raw Prometheus error text', async () => {
    const failing = vi.fn(async () => { throw new Error('connect ECONNREFUSED 10.1.2.3:9090') }) as unknown as typeof fetch
    const v = buildView(await fetchLiveSnapshot(failing), 'snapshot')
    expect(JSON.stringify(v)).not.toContain('10.1.2.3')
    expect(v.liveError).toBe('prometheus_unavailable')
  })
})

describe('EKS lifecycle', () => {
  const eol = [{ cycle: '1.99', releaseDate: '2030-01-01', eol: '2031-02-02', extendedSupport: '2032-03-03' }]
  const ok = (body: unknown) => vi.fn(async () => new Response(JSON.stringify(body), { status: 200 })) as unknown as typeof fetch

  it('maps endoflife.date cycles', () => {
    expect(mapEolToLifecycle(eol, null).versions['1.99']).toEqual({
      eks_release: '2030-01-01', end_of_standard_support: '2031-02-02', end_of_extended_support: '2032-03-03',
    })
    expect(() => mapEolToLifecycle([{ cycle: '1.98', eol: false }], null)).toThrow()
  })

  it('uses live data, caches 24h, and falls back to the snapshot on failure', async () => {
    const f = ok(eol)
    const live = await getEksLifecycle(f, 0)
    expect(live?.source).toBe('live (endoflife.date)')
    await getEksLifecycle(f, 3600_000)
    expect((f as unknown as ReturnType<typeof vi.fn>).mock.calls.length).toBe(1)

    resetLiveCaches()
    const failing = vi.fn(async () => new Response('x', { status: 503 })) as unknown as typeof fetch
    const snap = await getEksLifecycle(failing, 0)
    expect(snap?.source).toBe('snapshot')
  })
})
