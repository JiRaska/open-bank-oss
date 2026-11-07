// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { readContent } = vi.hoisted(() => ({ readContent: vi.fn() }))
vi.mock('@/lib/evidence/contentSource', () => ({ readAdminUiContentSnapshot: readContent }))
vi.mock('@/lib/discovery', () => ({
  discoverServicesSnapshot: vi.fn().mockResolvedValue({ status: 'static', services: [] }),
  prettyLabel: (name: string) => name,
}))

import { GET } from '@/app/api/services/health/route'
import { getProxyAllowlist, getRegistry } from '@/lib/services/fleet'

beforeEach(() => {
  readContent.mockReset()
  vi.stubEnv('SERVICES_HOST', 'localhost')
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ status: 'UP' }), { status: 200 })))
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs() })

describe('off-cluster health ownership publication overlay', () => {
  it('uses current published ownership while probing only image-pinned targets', async () => {
    const imageRegistry = getRegistry()
    const ledger = imageRegistry.find(service => service.container === 'openbank-ledger-service')!
    const imageLedgerDomain = ledger.group
    const allowlist = getProxyAllowlist()
    readContent.mockResolvedValue({ status: 'ready', snapshot: { content: { catalog: { services: [
      { name: ledger.container, dataDomain: 'treasury', port: 9999, container: 'untrusted-target' },
    ] } } } })
    const body = await (await GET()).json()
    expect(body.source).toBe('static')
    expect(body.services.find((service: { name: string }) => service.name === 'ledger-service')).toMatchObject({
      group: 'treasury', port: ledger.port, container: ledger.container, status: 'UP',
    })
    expect(readContent).toHaveBeenCalledTimes(1)
    const targets = vi.mocked(fetch).mock.calls.map(([url]) => String(url))
    expect(targets).toContain(`http://localhost:${ledger.port}/q/health/ready`)
    expect(targets.some(url => url.includes(':9999') || url.includes('untrusted-target'))).toBe(false)
    expect(getProxyAllowlist()).toEqual(allowlist)
    expect(imageRegistry.find(service => service.container === ledger.container)?.group).toBe(imageLedgerDomain)
  })

  it.each(['unavailable', 'legacy taxonomy', 'inconsistent ownership'])('retains image ownership when the content reader rejects %s', async reason => {
    readContent.mockResolvedValue({ status: 'unavailable', snapshot: null, error: reason })
    const body = await (await GET()).json()
    expect(body.services.find((service: { name: string }) => service.name === 'ledger-service')).toMatchObject({
      group: getRegistry().find(entry => entry.container === 'openbank-ledger-service')!.group, status: 'UP',
    })
  })
})
