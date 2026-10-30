// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from 'vitest'

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
