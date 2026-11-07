import { afterEach, describe, expect, it, vi } from 'vitest'
import { z } from 'zod'
import { getJson } from '@/lib/services/resourceClient'

afterEach(() => vi.unstubAllGlobals())

describe('shared validated resource client', () => {
  const schema = z.object({ id: z.string() })

  it('returns parsed data for a valid response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ id: 'one' })))
    await expect(getJson('/api/test', schema)).resolves.toEqual({ ok: true, data: { id: 'one' } })
  })

  it('keeps HTTP failures classified by the BFF contract', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ error: 'Unknown service: risk-engine' }, { status: 404 })))
    await expect(getJson('/api/test', schema)).resolves.toEqual({ ok: false, kind: 'not_deployed' })
  })

  it('returns a terminal contract error for malformed successful JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ id: 7 })))
    await expect(getJson('/api/test', schema)).resolves.toEqual({ ok: false, kind: 'error' })
  })

  it('classifies a failed request as unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    await expect(getJson('/api/test', schema)).resolves.toEqual({ ok: false, kind: 'unreachable' })
  })
})
