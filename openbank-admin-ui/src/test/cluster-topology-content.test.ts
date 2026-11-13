// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

const { readSnapshot } = vi.hoisted(() => ({ readSnapshot: vi.fn() }))

vi.mock('@/lib/evidence/contentSnapshot', () => ({
  createAdminUiContentSnapshotReader: () => ({ read: readSnapshot }),
}))

import { resolveAdminUiContent } from '@/lib/evidence/contentSource'
import type { AdminUiContentSnapshot } from '@/lib/evidence/contentSnapshot'

const provenance = {
  sourceSha: 'a'.repeat(40), runId: 1, runAttempt: 1, snapshot: 'snapshots/1-1-' + 'b'.repeat(64) + '.json',
  sha256: 'b'.repeat(64), collectedAt: '2026-10-20T10:00:00.000Z', expiresAt: '2026-10-20T11:00:00.000Z',
  pointerExpired: false, snapshotAgeMs: 0,
}

describe('cluster topology content source compatibility', () => {
  beforeEach(() => readSnapshot.mockReset())

  it('uses published topology when present and labels it as reviewed source', async () => {
    const published = { schema: 'openbank.cluster-topology/v1', source: 'reviewed GitOps', generatedAt: null }
    readSnapshot.mockResolvedValue({ status: 'ready', snapshot: { content: { clusterTopology: published } }, provenance })

    const result = await resolveAdminUiContent(
      snapshot => snapshot.content.clusterTopology,
      async () => null,
    )

    expect(result.value).toBe(published)
    expect(result.contentSource.source).toBe('published')
  })

  it('falls back to the baked topology for a valid older snapshot and labels it stale', async () => {
    const oldSnapshot = { content: {} } as AdminUiContentSnapshot
    readSnapshot.mockResolvedValue({ status: 'ready', snapshot: oldSnapshot, provenance })
    const baked = { schema: 'openbank.cluster-topology/v1', source: 'image file' }

    const result = await resolveAdminUiContent(
      snapshot => snapshot.content.clusterTopology,
      async () => baked,
    )

    expect(result.value).toBe(baked)
    expect(result.contentSource).toMatchObject({ source: 'image-baked', status: 'stale' })
  })
})


describe('generated namespace classification', () => {
  it('describes physical workload and tenant namespaces independently of business ownership', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'openbank-cluster-boundaries-'))
    try {
      const apps = path.join(repo, 'openbank-infra/gitops/apps')
      mkdirSync(apps, { recursive: true })
      const physicalNamespaces = ['ledger', 'ledger-pension-co', 'pension', 'pension-fund', 'new-workload']
      for (const name of physicalNamespaces) {
        writeFileSync(path.join(apps, `${name}.yaml`), `spec:\n  destination:\n    namespace: ${name}\n`)
      }
      const output = path.join(repo, 'topology.json')
      execFileSync(process.execPath, [path.resolve('scripts/generate-cluster-topology.mjs'), '--repo', repo, '--out', output])
      const topology = JSON.parse(readFileSync(output, 'utf8')) as {
        groups: Array<{ id: string; labelEn: string; blurb: { en: string } }>
        namespaces: Array<{ name: string; group: string; declared: boolean }>
        planVsReality: Array<{ item: { en: string }; plan: { en: string } }>
      }

      // Two pension contexts and an accounting tenant remain distinct physical namespaces.
      // Their existing group IDs stay compatible without asserting one namespace per business domain.
      for (const name of physicalNamespaces) {
        expect(topology.namespaces.find(namespace => namespace.name === name)).toMatchObject({
          name, group: 'domain', declared: true,
        })
      }
      expect(topology.groups.find(group => group.id === 'domain')).toMatchObject({
        labelEn: 'Application namespaces',
        blurb: { en: expect.stringContaining('Business ownership is classified independently in the service catalog.') },
      })
      expect(topology.planVsReality.find(row => row.item.en === 'Namespace segmentation')?.plan.en)
        .toBe('Technical separation of workloads and legal entities')
    } finally {
      rmSync(repo, { recursive: true, force: true })
    }
  })
})
