// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { collectServiceDiscovery } from '@/lib/discovery'

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
