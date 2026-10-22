// SPDX-License-Identifier: Apache-2.0
import { beforeEach, expect, it, vi } from 'vitest'
const { readSnapshot } = vi.hoisted(() => ({ readSnapshot: vi.fn() }))
vi.mock('@/lib/evidence/contentSnapshot', () => ({ createAdminUiContentSnapshotReader: () => ({ read: readSnapshot }) }))
import { getPlatformView, resetLiveCaches } from '@/lib/live-platform-versions'
import { platformVersions } from '@/lib/platform-versions'

const provenance = { sourceSha: 'a'.repeat(40), collectedAt: '2026-10-22T10:00:00.000Z', runId: 123, runAttempt: 1 }
const fetcher = vi.fn(async () => new Response(JSON.stringify({ status: 'success', data: { result: [] } }))) as typeof fetch
beforeEach(() => { resetLiveCaches(); readSnapshot.mockReset() })

it('updates declarations without an image rebuild while retaining unknown live observations', async () => {
  const declarations = structuredClone(platformVersions!)
  declarations.kubernetesVersion = '9.99'
  declarations.inventory.gitops.apps.push('new-platform-declaration')
  readSnapshot.mockResolvedValue({ status: 'ready', snapshot: { content: { platformVersions: declarations } }, provenance })
  const view = await getPlatformView(fetcher)
  expect(view.items.kubernetes.declared).toBe('9.99')
  expect(view.items.kubernetes.live).toBeNull()
  expect(view.inventory?.gitops.apps).toContain('new-platform-declaration')
  expect(view.declarationSource).toMatchObject({ source: 'published', status: 'ready', sourceSha: provenance.sourceSha })
  expect(platformVersions?.kubernetesVersion).not.toBe('9.99')
})

it('retains stale declarations with their original source identity', async () => {
  readSnapshot.mockResolvedValue({ status: 'stale', snapshot: { content: { platformVersions } }, provenance, error: 'manifest HTTP 503' })
  expect((await getPlatformView(fetcher)).declarationSource).toMatchObject({ source: 'published', status: 'stale', sourceSha: provenance.sourceSha })
})

it.each([
  { status: 'ready', snapshot: { content: {} }, provenance },
  { status: 'unavailable', snapshot: null, provenance: null, error: 'manifest HTTP 503' },
])('labels legacy or unavailable fallback as image-baked stale', async result => {
  readSnapshot.mockResolvedValue(result)
  const view = await getPlatformView(fetcher)
  expect(view.items.kubernetes.declared).toBe(platformVersions?.kubernetesVersion)
  expect(view.declarationSource).toMatchObject({ source: 'image-baked', status: 'stale', sourceCollectedAt: null })
})
