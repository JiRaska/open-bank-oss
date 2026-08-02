// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect } from 'vitest'
import { CLUSTER_INFRA, LOCAL_INFRA, probeInfra } from '@/lib/infra/probes'

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
      for (const d of map) expect(['http', 'tcp', 'absent']).toContain(d.probe.kind)
    }
  })

  it('an "absent" probe resolves to UNKNOWN without any network call', async () => {
    const res = await probeInfra({ id: 'temporal', probe: { kind: 'absent' } })
    expect(res.status).toBe('UNKNOWN')
    expect(res.latencyMs).toBeNull()
  })
})
