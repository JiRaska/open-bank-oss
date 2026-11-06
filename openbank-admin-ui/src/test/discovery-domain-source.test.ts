// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { readContent } = vi.hoisted(() => ({ readContent: vi.fn() }))
vi.mock('@/lib/evidence/contentSource', () => ({ readAdminUiContentSnapshot: readContent }))

import { collectServiceDiscovery, resolveServiceInSnapshot } from '@/lib/discovery'
import { getProxyAllowlist, getRegistry } from '@/lib/services/fleet'

function deployment(name: string, namespace: string) {
  return {
    metadata: { name, namespace },
    spec: { replicas: 1, template: { spec: { containers: [{ ports: [{ name: 'http', containerPort: 8101 }] }] } } },
    status: { replicas: 1, readyReplicas: 1 },
  }
}

async function collect() {
  return collectServiceDiscovery(async path => ({ items: path.includes('/deployments') ? [
    deployment('ledger-service', 'ledger'), deployment('ledger-service', 'ledger-pension-co'),
    deployment('new-service', 'ledger'), deployment('unregistered-service', 'ledger'),
  ] : [] }) as never, ['ledger'])
}

beforeEach(() => { readContent.mockReset() })
afterEach(() => vi.restoreAllMocks())

describe('discovery business ownership publication overlay', () => {
  it('uses one published ownership snapshot without changing pinned routes, ports or tenant selection', async () => {
    const imageRegistry = getRegistry()
    const imageLedgerDomain = imageRegistry.find(service => service.container === 'openbank-ledger-service')!.group
    const allowlist = getProxyAllowlist()
    // readAdminUiContentSnapshot returns only a validated coherent bundle. Other
    // content fields are irrelevant here; its reader's rejection cases are tested separately.
    readContent.mockResolvedValue({ status: 'ready', snapshot: { content: { catalog: { services: [
      { name: 'openbank-ledger-service', dataDomain: 'treasury', port: 9999, k8sNamespace: 'other' },
      { name: 'openbank-new-service', runnable: true, dataDomain: 'pensions', port: 9999 },
    ] } } } })

    const result = await collect()
    expect(readContent).toHaveBeenCalledTimes(1)
    expect(result.services.filter(service => service.name === 'ledger-service').map(service => service.group)).toEqual(['treasury', 'treasury'])
    expect(result.services.find(service => service.name === 'new-service')?.group).toBe('pensions')
    expect(result.services.find(service => service.name === 'unregistered-service')?.group).toBe('unclassified')
    expect(resolveServiceInSnapshot(result, 'ledger-service', 'ledger')).toMatchObject({
      state: 'found', baseUrl: 'http://ledger-service.ledger.svc:8101',
    })
    expect(resolveServiceInSnapshot(result, 'ledger-service')).toEqual({ state: 'unknown', coverage: 'complete' })
    expect(getProxyAllowlist()).toEqual(allowlist)
    expect(getRegistry().some(service => service.container === 'openbank-new-service')).toBe(false)
    expect(getRegistry()).toBe(imageRegistry)
    expect(imageRegistry.find(service => service.container === 'openbank-ledger-service')?.group).toBe(imageLedgerDomain)
  })

  it.each(['unavailable', 'legacy taxonomy', 'inconsistent ownership'])('retains image ownership after the content reader rejects %s', async reason => {
    readContent.mockResolvedValue({ status: 'unavailable', snapshot: null, error: reason })
    const result = await collect()
    expect(readContent).toHaveBeenCalledTimes(1)
    expect(result.services.filter(service => service.name === 'ledger-service').every(service => service.group === getRegistry().find(entry => entry.container === 'openbank-ledger-service')!.group)).toBe(true)
    expect(result.services.find(service => service.name === 'unregistered-service')?.group).toBe('unclassified')
    expect(resolveServiceInSnapshot(result, 'ledger-service', 'ledger')).toMatchObject({
      state: 'found', baseUrl: 'http://ledger-service.ledger.svc:8101',
    })
  })

  it('retains image ownership if the reader unexpectedly throws', async () => {
    readContent.mockRejectedValue(new Error('content unavailable'))
    const result = await collect()
    expect(result.services.find(service => service.name === 'ledger-service')?.group).toBe(getRegistry().find(entry => entry.container === 'openbank-ledger-service')!.group)
  })
})
