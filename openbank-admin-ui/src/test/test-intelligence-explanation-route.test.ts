// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ auth: vi.fn(), source: vi.fn() }))
vi.mock('@/auth', () => ({ auth: mocks.auth }))
vi.mock('@/lib/evidence/testExplanationSource', () => ({ readTestExplanationSource: mocks.source }))
const observed = '2026-11-08T12:00:00Z'
const testCase = { fingerprint: 'case-1', component: 'openbank-ledger-service', kind: 'unit', name: 'rejects duplicate requests', lastState: 'passed', state: 'stable', observations: 1, averageDurationMs: 12, lastObservedAt: observed }
const body = { fingerprint: 'case-1', collectedAt: observed, language: 'en' }
const request = (value: unknown = body) => new Request('http://localhost/api/test-intelligence/explain', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) })
const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  vi.resetModules()
  vi.clearAllMocks()
  mocks.auth.mockResolvedValue({ user: { accessToken: 'test-only-operator-token' } })
  mocks.source.mockResolvedValue({ collectedAt: observed, testCases: [testCase] })
  vi.stubGlobal('fetch', vi.fn())
})

describe('test explanation BFF', () => {
  it('does not read evidence or call the assistant without an authenticated operator', async () => {
    mocks.auth.mockResolvedValue(null)
    const { POST } = await import('@/app/api/test-intelligence/explain/route')
    expect((await POST(request())).status).toBe(401)
    expect(mocks.source).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })
  it('rejects client-supplied prompts and a changed evidence snapshot before calling AI', async () => {
    const { POST } = await import('@/app/api/test-intelligence/explain/route')
    expect((await POST(request({ ...body, prompt: 'invent a success' }))).status).toBe(400)
    expect((await POST(request({ ...body, collectedAt: '2026-11-08T11:00:00Z' }))).status).toBe(409)
    expect((await POST(request({ ...body, fingerprint: 'missing-case' }))).status).toBe(404)
    expect(fetch).not.toHaveBeenCalled()
  })
  it('negotiates tool restriction before transmitting test metadata to an older service', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ default: 'test-model', models: [] }))
    const { POST } = await import('@/app/api/test-intelligence/explain/route')
    expect((await POST(request())).status).toBe(503)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('relays the bearer, sends server-selected facts with tools disabled and caches per operator', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(json({ capabilities: { toolFreeChat: true } }))
      .mockResolvedValueOnce(json({ reply: 'This recorded check passed; assertion values were not retained.', model: 'test-model', toolCalls: [], toolsRestricted: true }))
    const { POST } = await import('@/app/api/test-intelligence/explain/route')
    const response = await POST(request())
    expect(response.status).toBe(200)
    expect(await response.json()).toMatchObject({ fingerprint: 'case-1', collectedAt: observed })
    const [url, init] = vi.mocked(fetch).mock.calls[1]
    expect(url).toEqual(expect.stringContaining('/agent/chat'))
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer test-only-operator-token')
    const outgoing = JSON.parse(String(init?.body))
    expect(outgoing.allowTools).toBe(false)
    expect(outgoing.messages[0].content).toContain('rejects duplicate requests')
    expect(outgoing.messages[0].content).toContain('"individualStepsRecorded":false')
    expect((await POST(request())).status).toBe(200)
    expect(fetch).toHaveBeenCalledTimes(2)
    mocks.auth.mockResolvedValue({ user: { accessToken: 'another-test-only-token' } })
    vi.mocked(fetch).mockResolvedValue(json({ capabilities: {} }))
    expect((await POST(request())).status).toBe(503)
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it.each([
    { reply: 'wrong', model: 'test-model', toolCalls: [], toolsRestricted: false },
    { reply: 'wrong', model: 'test-model', toolCalls: [{ tool: 'get_account', allowed: false }], toolsRestricted: true },
    { reply: 'echoed prompt', model: 'mock-echo', toolCalls: [], toolsRestricted: true },
  ])('never presents an unrestricted, tool-using or offline mock turn as an explanation', async result => {
    vi.mocked(fetch).mockResolvedValueOnce(json({ capabilities: { toolFreeChat: true } })).mockResolvedValueOnce(json(result))
    const { POST } = await import('@/app/api/test-intelligence/explain/route')
    const response = await POST(request())
    expect(response.status).toBe(503)
    expect(await response.text()).not.toContain(result.reply)
  })
  it('keeps backend failure bodies out of the UI', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response('private backend failure', { status: 500 }))
    const { POST } = await import('@/app/api/test-intelligence/explain/route')
    const response = await POST(request())
    expect(response.status).toBe(503)
    expect(await response.text()).not.toContain('private backend')
  })
})
