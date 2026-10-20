// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { resolveContent } = vi.hoisted(() => ({ resolveContent: vi.fn() }))

vi.mock('@/lib/evidence/contentSource', () => ({ resolveAdminUiContent: resolveContent }))

import { GET } from '@/app/api/cluster/topology/route'

describe('cluster topology route compatibility', () => {
  beforeEach(() => resolveContent.mockReset())

  it('keeps topology fields at the existing response top level and adds source metadata', async () => {
    resolveContent.mockResolvedValue({
      value: { schema: 'openbank.cluster-topology/v1', counts: { namespaces: 5 }, groups: [] },
      contentSource: { source: 'published', status: 'ready', sourceSha: 'a'.repeat(40), sourceCollectedAt: '2026-10-20T10:00:00.000Z', imageBuiltAt: null, fetchedAt: '2026-10-20T10:01:00.000Z' },
    })

    const response = await GET()
    const body = await response.json()

    expect(body).toMatchObject({
      schema: 'openbank.cluster-topology/v1',
      counts: { namespaces: 5 },
      contentSource: { source: 'published', status: 'ready' },
    })
    expect(body.topology).toBeUndefined()
  })
})
