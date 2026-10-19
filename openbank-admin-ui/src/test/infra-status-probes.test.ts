// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { CLUSTER_INFRA, LOCAL_INFRA, probeInfra } from '@/lib/infra/probes'
import { IMAGE_PATTERNS } from '@/lib/live-platform-versions'

// The infra ids are a stable contract consumed by /docs/bcp and /infrastructure.
// These checks give the probe map consistent coverage without depending on a live
// cluster (the review ask on #764).

describe('infra-status probe definitions', () => {
  it('includes the platform control plane + orchestration ids in the cluster map', () => {
    const ids = new Set(CLUSTER_INFRA.map(d => d.id))
    for (const id of ['temporal', 'keda', 'argocd', 'kyverno', 'cert-manager', 'karpenter']) {
      expect(ids.has(id), `CLUSTER_INFRA missing ${id}`).toBe(true)
    }
  })

  it('probes Temporal on its verified frontend Service DNS + port', () => {
    const temporal = CLUSTER_INFRA.find(d => d.id === 'temporal')
    expect(temporal?.probe).toEqual({ kind: 'tcp', host: 'temporal-frontend.temporal.svc', port: 7233 })
  })

  it('uses Kafka’s only configured listener and never probes the removed plaintext port', () => {
    expect(CLUSTER_INFRA.find(d => d.id === 'kafka')?.probe).toEqual({
      kind: 'tcp', host: 'openbank-cluster-kafka-bootstrap.messaging.svc', port: 9093,
    })
  })

  it('uses Prometheus readiness for Valkey because its NetworkPolicy blocks cross-namespace probes', () => {
    expect(CLUSTER_INFRA.find(d => d.id === 'valkey')?.probe).toEqual({
      kind: 'prometheus-deployment', namespace: 'accounts', deployment: 'redis',
    })
  })

  it('reports failed TCP probes as unknown, not confirmed component outages', async () => {
    const result = await probeInfra({ id: 'test', probe: { kind: 'tcp', host: '127.0.0.1', port: 1 } })
    expect(result.status).toBe('UNKNOWN')
    expect(result.checkedAt).not.toBeNull()
  })

  it('uses ready replica metrics for Valkey and treats a missing series as unknown', async () => {
    const original = globalThis.fetch
    try {
      globalThis.fetch = vi.fn(async () => Response.json({ status: 'success', data: { result: [{ value: [0, '1'] }] } }))
      await expect(probeInfra({ id: 'valkey', probe: { kind: 'prometheus-deployment', namespace: 'accounts', deployment: 'redis' } }))
        .resolves.toMatchObject({ status: 'UP' })
      globalThis.fetch = vi.fn(async () => Response.json({ status: 'success', data: { result: [] } }))
      await expect(probeInfra({ id: 'valkey', probe: { kind: 'prometheus-deployment', namespace: 'accounts', deployment: 'redis' } }))
        .resolves.toMatchObject({ status: 'UNKNOWN' })
    } finally {
      globalThis.fetch = original
    }
  })

  it('registers the new platform ids in both maps (in-cluster + off-cluster)', () => {
    // Off-cluster the control plane isn't deployed, so it must be present as an
    // `absent` probe (→ UNKNOWN) rather than missing entirely (which would leave
    // the /infrastructure card with no status at all).
    const clusterIds = new Set(CLUSTER_INFRA.map(d => d.id))
    const localMap = new Map(LOCAL_INFRA.map(d => [d.id, d.probe]))
    for (const id of ['temporal', 'keda', 'argocd', 'kyverno', 'cert-manager', 'karpenter']) {
      expect(clusterIds.has(id), `CLUSTER_INFRA missing ${id}`).toBe(true)
      expect(localMap.get(id), `LOCAL_INFRA missing ${id}`).toEqual({ kind: 'absent' })
    }
  })

  it('every probe has a valid kind and no duplicate ids', () => {
    for (const map of [CLUSTER_INFRA, LOCAL_INFRA]) {
      const ids = map.map(d => d.id)
      expect(new Set(ids).size, 'duplicate infra id').toBe(ids.length)
      for (const d of map) expect(['http', 'tcp', 'prometheus-deployment', 'absent']).toContain(d.probe.kind)
    }
  })

  it('keeps lifecycle catalog coverage aligned with every in-cluster status component', () => {
    const registryPath = path.resolve(process.cwd(), 'src/lib/infra-lifecycle/registry.json')
    const registry = JSON.parse(readFileSync(registryPath, 'utf8')) as { components: Array<{ id: string }> }
    const snapshotPath = path.resolve(process.cwd(), 'infra-lifecycle.json')
    const snapshot = JSON.parse(readFileSync(snapshotPath, 'utf8')) as { components: Array<{ id: string }> }
    const ids = registry.components.map(c => c.id)
    expect(new Set(ids).size, 'duplicate lifecycle component id').toBe(ids.length)
    expect(new Set(ids)).toEqual(new Set(CLUSTER_INFRA.map(c => c.id)))
    expect(snapshot.components.map(c => c.id)).toEqual(ids)
    for (const id of ids) {
      expect(IMAGE_PATTERNS[id], `lifecycle component ${id} has no runtime image version parser`).toBeDefined()
    }
  })

  it('an "absent" probe resolves to UNKNOWN without any network call', async () => {
    const res = await probeInfra({ id: 'temporal', probe: { kind: 'absent' } })
    expect(res.status).toBe('UNKNOWN')
    expect(res.latencyMs).toBeNull()
  })
})
