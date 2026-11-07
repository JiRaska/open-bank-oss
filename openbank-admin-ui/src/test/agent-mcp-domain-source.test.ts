// SPDX-License-Identifier: Apache-2.0
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('@/auth', () => ({ auth: vi.fn() }))
vi.mock('@/lib/agent/svidMint', () => ({ mintSvidHeaders: vi.fn().mockResolvedValue(null) }))
vi.mock('@/lib/services/fleet', () => ({ getRegistry: vi.fn().mockReturnValue([]) }))
vi.mock('@/lib/services/domainOwnership', () => ({ currentDomainOwnership: vi.fn() }))
import { auth } from '@/auth'
import { currentDomainOwnership } from '@/lib/services/domainOwnership'
import { POST } from '@/app/api/agent/mcp/route'

const tool = { name: 'list_ledger_journals', service: 'ledger-service', domain: 'Core Banking',
  inputSchema: { type: 'object', properties: { ledgerId: { type: 'string' } } } }
function request(method: string) {
  return new NextRequest('http://localhost/api/agent/mcp', { method: 'POST',
    body: JSON.stringify({ jsonrpc: '2.0', id: 7, method, params: { arguments: { ledgerId: 'example' } } }) })
}
describe('MCP domain source boundary', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'test-operator' } } as never)
    vi.mocked(currentDomainOwnership).mockResolvedValue({
      byModule: new Map([['openbank-ledger-service', 'accounting']]), byWorkload: new Map(),
    })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('projects published ownership while preserving tools, schemas, session and relay target', async () => {
    const upstream = { jsonrpc: '2.0', id: 7, result: { tools: [tool], cursor: 'next' } }
    const relay = vi.fn().mockResolvedValue(Response.json(upstream))
    vi.stubGlobal('fetch', relay)
    const req = request('tools/list')
    const body = await req.clone().json()
    const res = await POST(req)
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({ ...upstream, result: { ...upstream.result,
      tools: [{ ...tool, domain: 'Financial accounting' }] } })
    const [url, init] = relay.mock.calls[0]
    expect(url).toBe('http://localhost:8109/mcp')
    expect(JSON.parse(init.body)).toEqual(body)
    expect(init.headers.Authorization).toBe('Bearer test-operator')
    expect(init.headers['X-Agent-Id']).toBe('ui-assistant')
  })

  it.each(['initialize', 'tools/call'])('preserves %s responses without reading ownership', async method => {
    const result = { jsonrpc: '2.0', id: 7, result: { tools: [tool], content: [] } }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(result)))
    expect(await (await POST(request(method))).json()).toEqual(result)
    expect(currentDomainOwnership).not.toHaveBeenCalled()
  })

  it('preserves protocol failures without rewriting attached data', async () => {
    const result = { jsonrpc: '2.0', id: 7, error: { code: -32603 }, result: { tools: [tool] } }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(result)))
    expect(await (await POST(request('tools/list'))).json()).toEqual(result)
    expect(currentDomainOwnership).not.toHaveBeenCalled()
  })

  it('refuses an unauthenticated relay before ownership or upstream access', async () => {
    vi.mocked(auth).mockResolvedValue(null)
    const relay = vi.fn()
    vi.stubGlobal('fetch', relay)
    expect((await POST(request('tools/list'))).status).toBe(401)
    expect(relay).not.toHaveBeenCalled()
    expect(currentDomainOwnership).not.toHaveBeenCalled()
  })
})
