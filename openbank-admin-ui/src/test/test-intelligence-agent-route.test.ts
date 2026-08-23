// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mkdtempSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'

describe('Test Intelligence agent BFF', () => {
  beforeEach(() => { vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'viewer-token', roles: ['ROLE_ADMIN'] } } as never) })
  afterEach(() => { vi.restoreAllMocks(); delete process.env.OPENBANK_TEST_INTELLIGENCE })

  it('relays the operator token to flaky-test-hunter', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify([{ id: 'f-1' }]), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/test-intelligence/agents/route')
    const body = await (await GET()).json()
    expect(body).toEqual({ findings: [{ id: 'f-1' }], available: true })
    expect(new Headers(fetchMock.mock.calls[0][1].headers).get('Authorization')).toBe('Bearer viewer-token')
  })

  it('never reaches the agent without a session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/test-intelligence/agents/route')
    expect((await GET()).status).toBe(401)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('does not let a viewer spend the AI analysis budget', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'viewer-token', roles: ['ROLE_VIEWER'] } } as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/test-intelligence/agents/route')
    expect((await POST()).status).toBe(403)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('sends only the bounded evidence projection for operator-triggered AI analysis', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-agent-'))
    const file = path.join(dir, 'report.json')
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: '2026-08-22T10:00:00Z',
      components: [{ component: 'openbank-ledger-service', moneyPath: true,
        evidence: [{ kind: 'integration', state: 'stale', source: '/private/path' }],
        testInfrastructure: { declared: ['postgres'], observed: [{ lifecycle: 'started' }] } }],
    }))
    process.env.OPENBANK_TEST_INTELLIGENCE = file
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify([{ id: 'diagnosed-1' }]), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/test-intelligence/agents/route')
    const body = await (await POST()).json()
    expect(body.findings).toEqual([{ id: 'diagnosed-1' }])
    const outbound = JSON.parse(fetchMock.mock.calls[0][1].body as string)
    expect(outbound.components[0]).toEqual({ component: 'openbank-ledger-service', moneyPath: true,
      evidence: [{ kind: 'integration', state: 'stale' }], declaredInfrastructure: ['postgres'], observedInfrastructureStarts: 1 })
    expect(JSON.stringify(outbound)).not.toContain('/private/path')
    rmSync(dir, { recursive: true, force: true })
  })
})
