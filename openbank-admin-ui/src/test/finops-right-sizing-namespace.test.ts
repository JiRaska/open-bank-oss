// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GET } from '@/app/api/finops/right-sizing/route'

afterEach(() => vi.unstubAllGlobals())
describe('FinOps namespace observations', () => {
  it('does not attribute a shared namespace to one service or business domain', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string) => input.endsWith('/-/ready')
      ? new Response('ok')
      : Response.json({ status: 'success', data: { result: ['platform', 'accounts'].map(namespace => ({
        metric: { namespace }, value: [1, '100'],
      })) } })))
    const body = await (await GET()).json()
    expect(body.available).toBe(true)
    expect(body.services.find((row: { namespace: string }) => row.namespace === 'platform').displayName).toBe('platform')
    expect(body.services.find((row: { namespace: string }) => row.namespace === 'accounts').displayName).toBe('accounts')
  })
})
