// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { collectServiceDiscovery, resolveServiceInSnapshot } from '@/lib/discovery'

function deployment(name: string, namespace: string) {
  return {
    metadata: { name, namespace },
    spec: { replicas: 1, template: { spec: { containers: [{ ports: [{ name: 'http', containerPort: 8100 }] }] } } },
    status: { replicas: 1, readyReplicas: 1 },
  }
}

describe('Kubernetes service discovery coverage', () => {
  it('returns unavailable and no inventory when every namespaced list fails', async () => {
    const requested: string[] = []
    const result = await collectServiceDiscovery(async (path) => {
      requested.push(path)
      throw new Error('Kubernetes API unavailable')
    }, ['accounts', 'payments'])

    expect(result).toEqual({ status: 'unavailable', services: [] })
    expect(requested).toHaveLength(4)
  })

  it('preserves observed services but marks the inventory partial when a list fails', async () => {
    const result = await collectServiceDiscovery(async (path) => {
      if (path.includes('/payments/')) throw new Error('Forbidden')
      if (path.includes('/deployments')) {
        return { items: [deployment('account-service', 'accounts')] } as never
      }
      return { items: [] } as never
    }, ['accounts', 'payments'])

    expect(result.status).toBe('partial')
    expect(result.services.map(({ name, namespace, ready }) => ({ name, namespace, ready }))).toEqual([
      { name: 'account-service', namespace: 'accounts', ready: true },
    ])
  })

  it('accepts an empty inventory only after every required list succeeds', async () => {
    const result = await collectServiceDiscovery(async () => ({ items: [] }) as never, ['accounts'])
    expect(result).toEqual({ status: 'complete', services: [] })
  })

  it('does not call a truncated Kubernetes page complete', async () => {
    const result = await collectServiceDiscovery(async (path) => {
      if (path.includes('/deployments')) {
        return { items: [deployment('account-service', 'accounts')], metadata: { continue: 'next-page' } } as never
      }
      return { items: [] } as never
    }, ['accounts'])

    expect(result.status).toBe('partial')
    expect(result.services.map(({ name }) => name)).toEqual(['account-service'])
  })
})


describe('tri-state workload resolution', () => {
  const observed = { name: 'account-service', namespace: 'accounts', port: 8100, group: 'core' as const, ready: true, replicas: 1, readyReplicas: 1, scaledToZero: false }

  it('returns a partial positive with its Service DNS target', () => {
    expect(resolveServiceInSnapshot({ status: 'partial', services: [observed] }, 'account-service')).toMatchObject({
      state: 'found', coverage: 'partial', baseUrl: 'http://account-service.accounts.svc:8100',
    })
  })

  it('returns unknown for an absent name in partial or unavailable inventory', () => {
    expect(resolveServiceInSnapshot({ status: 'partial', services: [observed] }, 'ledger-service'))
      .toEqual({ state: 'unknown', coverage: 'partial' })
    expect(resolveServiceInSnapshot({ status: 'unavailable', services: [] }, 'ledger-service'))
      .toEqual({ state: 'unknown', coverage: 'unavailable' })
  })

  it('confirms absence only from complete inventory and preserves off-cluster mode', () => {
    expect(resolveServiceInSnapshot({ status: 'complete', services: [] }, 'ledger-service'))
      .toEqual({ state: 'not_found', coverage: 'complete' })
    expect(resolveServiceInSnapshot({ status: 'static', services: [] }, 'ledger-service'))
      .toEqual({ state: 'off_cluster', coverage: 'static' })
  })

  it('treats duplicate names as ambiguous even in an otherwise complete snapshot', () => {
    const duplicate = { ...observed, namespace: 'payments' }
    expect(resolveServiceInSnapshot({ status: 'complete', services: [observed, duplicate] }, 'account-service'))
      .toEqual({ state: 'unknown', coverage: 'complete' })
  })

  it('uses a closed canonical namespace preference only for the matching identity', () => {
    const bankLedger = { ...observed, name: 'ledger-service', namespace: 'ledger' }
    const pensionLedger = { ...observed, name: 'ledger-service', namespace: 'ledger-pension-co' }
    const both = { status: 'complete' as const, services: [bankLedger, pensionLedger] }

    expect(resolveServiceInSnapshot(both, 'ledger-service', 'ledger')).toMatchObject({
      state: 'found', service: bankLedger, baseUrl: 'http://ledger-service.ledger.svc:8100',
    })
    expect(resolveServiceInSnapshot({ status: 'complete', services: [pensionLedger] }, 'ledger-service', 'ledger'))
      .toEqual({ state: 'not_found', coverage: 'complete' })
    expect(resolveServiceInSnapshot({ status: 'partial', services: [pensionLedger] }, 'ledger-service', 'ledger'))
      .toEqual({ state: 'unknown', coverage: 'partial' })
  })
})
