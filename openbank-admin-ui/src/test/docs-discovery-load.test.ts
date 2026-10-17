// SPDX-License-Identifier: Apache-2.0
import { EventEmitter } from 'node:events'
import https from 'node:https'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('node:fs', () => ({
  existsSync: () => true,
  readFileSync: () => 'fixture-token',
  default: { existsSync: () => true, readFileSync: () => 'fixture-token' },
}))
vi.mock('node:https', () => ({ default: { request: vi.fn() } }))

afterEach(() => {
  vi.resetModules()
  vi.resetAllMocks()
  delete process.env.OPENBANK_NAMESPACES
  delete process.env.KUBERNETES_SERVICE_HOST
})

describe('docs inventory under concurrent load', () => {
  it('shares one Kubernetes discovery request across a fleet of docs cards', async () => {
    process.env.OPENBANK_NAMESPACES = 'fixture'
    process.env.KUBERNETES_SERVICE_HOST = 'kubernetes.fixture'
    vi.mocked(https.request).mockImplementation((options: unknown, onResponse: unknown) => {
      const request = new EventEmitter() as EventEmitter & { end: () => void }
      request.end = () => {
        queueMicrotask(() => {
          const response = new EventEmitter() as EventEmitter & { statusCode: number }
          response.statusCode = 200
          ;(onResponse as (res: typeof response) => void)(response)
          const items = String((options as { path: string }).path).includes('/deployments')
            ? [{
                metadata: { name: 'account-service', namespace: 'fixture' },
                spec: { replicas: 1, template: { spec: { containers: [{ ports: [{ name: 'http', containerPort: 8100 }] }] } } },
                status: { replicas: 1, readyReplicas: 1 },
              }]
            : []
          response.emit('data', Buffer.from(JSON.stringify({ items })))
          response.emit('end')
        })
      }
      return request as ReturnType<typeof https.request>
    })
    const { resolveInClusterBaseUrl } = await import('@/lib/discovery')
    const urls = await Promise.all(Array.from({ length: 30 }, () => resolveInClusterBaseUrl('account-service')))
    expect(new Set(urls)).toEqual(new Set(['http://account-service.fixture.svc:8100']))
    // One Deployment list and one Rollout list, independent of card count.
    expect(https.request).toHaveBeenCalledTimes(2)
  })
})
