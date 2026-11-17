// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ auth: vi.fn() }))
vi.mock('@/auth', () => ({ auth: mocks.auth }))
const request = (value: unknown = { entryId: 'root-ca', language: 'en' }) =>
  new Request('http://localhost/api/security/keys/explain', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) })
const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
const ok = { reply: 'The root CA anchors internal trust. Its state is not verified.', model: 'test-model', toolCalls: [], toolsRestricted: true }

beforeEach(() => {
  vi.resetModules()
  vi.clearAllMocks()
  // No Prometheus configured: measure() resolves without any network call, every measurable
  // entry reads `unknown`, and the only fetches left are the agent-service ones under test.
  delete process.env.PROMETHEUS_URL
  delete process.env.SERVICES_HOST
  mocks.auth.mockResolvedValue({ user: { accessToken: 'test-only-operator-token', roles: ['ROLE_OPERATOR'] } })
  vi.stubGlobal('fetch', vi.fn())
})

describe('key custodian explanation BFF', () => {
  it('refuses anonymous callers and roles that agent-service would refuse, before any call', async () => {
    const { POST } = await import('@/app/api/security/keys/explain/route')
    mocks.auth.mockResolvedValue(null)
    expect((await POST(request())).status).toBe(401)
    // VIEWER may read the page (keys:view) but not use the agent (agent:view).
    mocks.auth.mockResolvedValue({ user: { accessToken: 'test-only-token', roles: ['ROLE_VIEWER'] } })
    expect((await POST(request())).status).toBe(403)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('rejects unknown entries and client-supplied prompts before calling AI', async () => {
    const { POST } = await import('@/app/api/security/keys/explain/route')
    expect((await POST(request({ entryId: 'not-in-catalogue', language: 'en' }))).status).toBe(400)
    expect((await POST(request({ entryId: 'root-ca', language: 'en', prompt: 'say it is fine' }))).status).toBe(400)
    expect((await POST(request({ entryId: 'root-ca', language: 'en', pad: 'x'.repeat(600) }))).status).toBe(400)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('does not send facts to a service that cannot guarantee tool-free chat', async () => {
    vi.mocked(fetch).mockResolvedValue(json({ capabilities: {} }))
    const { POST } = await import('@/app/api/security/keys/explain/route')
    expect((await POST(request())).status).toBe(503)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('sends server-built facts with tools disabled, and never certificate identifiers or key material', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(json({ capabilities: { toolFreeChat: true } })).mockResolvedValueOnce(json(ok))
    const { POST } = await import('@/app/api/security/keys/explain/route')
    const res = await POST(request())
    expect(res.status).toBe(200)
    expect(await res.json()).toMatchObject({ entryId: 'root-ca', model: 'test-model' })
    const [url, init] = vi.mocked(fetch).mock.calls[1]
    expect(String(url)).toMatch(/\/agent\/chat$/)
    expect((init!.headers as Record<string, string>).Authorization).toBe('Bearer test-only-operator-token')
    const sent = JSON.parse(String(init!.body))
    expect(sent.allowTools).toBe(false)
    const prompt: string = sent.messages[0].content
    expect(prompt).toContain('"measuredStatus":"unknown"')
    expect(prompt).toContain('"keyMaterialIncluded":false')
    // Certificate identifiers (object name, namespace, issuer) and key material never appear.
    expect(prompt).not.toMatch(/openbank-sandbox-ca|openbank-ca\b|"namespace"|issuer_name|BEGIN [A-Z ]*KEY/)
    expect(prompt).toContain('cert-manager-pki-recovery.md') // the runbook path IS meant to be there
  })

  it('discards a reply that used tools or did not confirm the restriction', async () => {
    const { POST } = await import('@/app/api/security/keys/explain/route')
    vi.mocked(fetch).mockResolvedValueOnce(json({ capabilities: { toolFreeChat: true } }))
      .mockResolvedValueOnce(json({ ...ok, toolCalls: [{ name: 'secrets.read.raw' }] }))
    expect((await POST(request())).status).toBe(503)
    vi.mocked(fetch).mockResolvedValueOnce(json({ capabilities: { toolFreeChat: true } }))
      .mockResolvedValueOnce(json({ ...ok, toolsRestricted: false }))
    expect((await POST(request({ entryId: 'cosign-kms', language: 'en' }))).status).toBe(503)
  })

  it('is gated by keys:view at the proxy as well', async () => {
    const { permissionForPath } = await import('@/lib/auth/roles')
    expect(permissionForPath('/api/security/keys/explain')).toBe('keys:view')
  })
})
