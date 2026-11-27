// SPDX-License-Identifier: Apache-2.0

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { auth } from '@/auth'
import { PUT } from '@/app/api/parties/[id]/mcp-native-passkey-age-exception/route'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
vi.mock('@/lib/services/bff', () => ({
  serverSvcUrl: (_service: string, _namespace: string, _port: number, path: string) =>
    `https://party.invalid${path}`,
}))

const partyId = '12345678-0000-0000-0000-123456789abc'
const context = { params: Promise.resolve({ id: partyId }) }
const policy = { minimumAgeMinutes: 1, expiresAt: '2027-01-01T00:15:00Z', reason: 'Sandbox pairing test' }
const request = (body: object) => new Request('https://admin.invalid/', {
  method: 'PUT', body: JSON.stringify(body),
})
const upstream = vi.fn()

function session(role: string) {
  vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'test-token', roles: [role] } } as never)
}

beforeEach(() => {
  vi.stubEnv('OPENBANK_MCP_SANDBOX_PASSKEY_AGE_EXCEPTIONS', 'true')
  vi.stubGlobal('fetch', upstream)
  upstream.mockReset()
  session('ROLE_ADMIN')
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})

describe('sandbox MCP passkey policy authority proxy', () => {
  it('refuses the change when sandbox support is disabled', async () => {
    vi.stubEnv('OPENBANK_MCP_SANDBOX_PASSKEY_AGE_EXCEPTIONS', 'false')
    expect((await PUT(request(policy), context)).status).toBe(403)
    expect(upstream).not.toHaveBeenCalled()
  })

  it('requires the administrator role and positive minimum age', async () => {
    session('ROLE_OPERATOR')
    expect((await PUT(request(policy), context)).status).toBe(403)
    session('ROLE_ADMIN')
    expect((await PUT(request({ ...policy, minimumAgeMinutes: 0 }), context)).status).toBe(400)
    expect(upstream).not.toHaveBeenCalled()
  })

  it('forwards an allowed request to Party without caller-supplied identity fields', async () => {
    upstream.mockResolvedValue(Response.json({ ...policy, partyId }))
    const response = await PUT(request({ ...policy, partyId: 'different-party', actor: 'forged' }), context)
    expect(response.status).toBe(200)
    const [url, init] = upstream.mock.calls[0]
    expect(url).toBe(`https://party.invalid/api/v1/parties/${partyId}/mcp-native-passkey-age-exception`)
    expect(JSON.parse(init.body)).toEqual(policy)
    expect(init.headers.authorization).toBe('Bearer test-token')
  })
})
