// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { readSnapshot } = vi.hoisted(() => ({ readSnapshot: vi.fn() }))

vi.mock('server-only', () => ({}))
vi.mock('@/lib/evidence/contentSnapshot', () => ({
  createAdminUiContentSnapshotReader: () => ({ read: readSnapshot }),
}))

import { resolveAdminUiContent } from '@/lib/evidence/contentSource'

const provenance = {
  sourceSha: 'a'.repeat(40), runId: 123, runAttempt: 1, snapshot: `snapshots/123-1-${'b'.repeat(64)}.json`,
  sha256: 'b'.repeat(64), collectedAt: '2026-10-19T10:00:00.000Z', expiresAt: '2026-10-19T12:00:00.000Z',
  pointerExpired: false, snapshotAgeMs: 1,
}

function published(services: Array<{ name: string }>, status: 'ready' | 'stale' = 'ready') {
  return {
    status,
    snapshot: { content: { catalog: { services } } },
    provenance,
    ...(status === 'stale' ? { error: 'manifest HTTP 503' } : {}),
  }
}

describe('reviewed content consumers', () => {
  beforeEach(() => {
    readSnapshot.mockReset()
    process.env.BUILD_GIT_SHA = 'c'.repeat(40)
    process.env.BUILD_DATE = '2026-10-18T09:00:00.000Z'
  })

  it('serves a new catalog entry from the published source without an image rebuild', async () => {
    readSnapshot.mockResolvedValue(published([{ name: 'existing-service' }, { name: 'new-service' }]))
    const fallback = vi.fn(async () => [{ name: 'existing-service' }])
    const result = await resolveAdminUiContent(snapshot => snapshot.content.catalog.services as Array<{ name: string }>, fallback)

    expect(result.value?.map(service => service.name)).toEqual(['existing-service', 'new-service'])
    expect(result.contentSource).toMatchObject({ source: 'published', status: 'ready', sourceSha: 'a'.repeat(40), sourceCollectedAt: provenance.collectedAt })
    expect(fallback).not.toHaveBeenCalled()
  })

  it('keeps a last-good source visibly stale through an outage, then recovers to the next published revision', async () => {
    readSnapshot
      .mockResolvedValueOnce(published([{ name: 'v1-service' }]))
      .mockResolvedValueOnce(published([{ name: 'v1-service' }], 'stale'))
      .mockResolvedValueOnce(published([{ name: 'v2-service' }, { name: 'new-service' }]))
    const fallback = vi.fn(async () => [{ name: 'image-service' }])
    const select = (snapshot: { content: { catalog: { services: Array<{ name: string }> } } }) => snapshot.content.catalog.services

    const first = await resolveAdminUiContent(select, fallback)
    const outage = await resolveAdminUiContent(select, fallback)
    const recovered = await resolveAdminUiContent(select, fallback)

    expect(first.value?.map(service => service.name)).toEqual(['v1-service'])
    expect(first.contentSource.status).toBe('ready')
    expect(outage.value?.map(service => service.name)).toEqual(['v1-service'])
    expect(outage.contentSource).toMatchObject({ source: 'published', status: 'stale', sourceSha: 'a'.repeat(40) })
    expect(recovered.value?.map(service => service.name)).toEqual(['v2-service', 'new-service'])
    expect(recovered.contentSource.status).toBe('ready')
    expect(fallback).not.toHaveBeenCalled()
  })

  it('labels bundled fallback stale and separates image time from request fetch time', async () => {
    readSnapshot.mockResolvedValue({ status: 'unavailable', snapshot: null, provenance: null, error: 'manifest HTTP 503' })
    const result = await resolveAdminUiContent(
      snapshot => snapshot.content.catalog,
      async () => ({ services: [{ name: 'image-service' }] }),
    )

    expect(result.value).toEqual({ services: [{ name: 'image-service' }] })
    expect(result.contentSource).toMatchObject({
      source: 'image-baked', status: 'stale', sourceSha: 'c'.repeat(40),
      imageBuiltAt: '2026-10-18T09:00:00.000Z', sourceCollectedAt: null,
      detail: 'manifest HTTP 503',
    })
    expect(result.contentSource.fetchedAt).not.toBe(result.contentSource.imageBuiltAt)
  })
})
