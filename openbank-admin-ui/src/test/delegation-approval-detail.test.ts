// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import fs from 'node:fs'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'

const APPROVAL = '018f4a3c-1b2d-7e00-9a11-000000000010'
const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } }

async function call(id: string) {
  const { GET } = await import('@/app/api/delegations/approvals/[id]/route')
  return GET({} as never, { params: Promise.resolve({ id }) })
}

describe('delegation lifecycle approval detail BFF', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })

  afterEach(() => vi.restoreAllMocks())

  it('requires a session and validates the id before calling upstream', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    vi.mocked(auth).mockResolvedValue(null as never)
    expect((await call(APPROVAL)).status).toBe(401)
    vi.mocked(auth).mockResolvedValue(SESSION as never)
    expect((await call('not-an-id')).status).toBe(400)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('relays the operator bearer and immutable evidence', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      id: APPROVAL,
      delegationId: '018f4a3c-1b2d-7e00-9a11-000000000011',
      operation: 'SUSPEND',
      state: 'PROPOSED',
      proposedBy: 'maker',
      proposedAt: '2026-09-02T08:00:00Z',
    }), { status: 200 })))

    const response = await call(APPROVAL)
    expect(response.status).toBe(200)
    expect((await response.json()).operation).toBe('SUSPEND')
    const [url, init] = vi.mocked(global.fetch).mock.calls[0] as unknown as [string, RequestInit]
    expect(String(url)).toContain(`/api/v1/delegations/approvals/${APPROVAL}`)
    expect(new Headers(init.headers).get('authorization')).toBe('Bearer operator-token')
  })

  it('sanitises upstream failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"sql":"private"}', { status: 500 })))
    const response = await call(APPROVAL)
    expect(response.status).toBe(502)
    expect(await response.json()).toEqual({ error: 'upstream_error' })
  })
})

describe('delegation approval UI stays read-only and accessible', () => {
  const page = fs.readFileSync(
    path.join(process.cwd(), 'src/app/approvals/delegation/[id]/page.tsx'),
    'utf8',
  )

  it('has explicit loading, retry, timeline and read-only states', () => {
    expect(page).toContain('role="status"')
    expect(page).toContain('aria-live="polite"')
    expect(page).toContain('Evidence timeline')
    expect(page).toContain('This screen is read-only.')
    expect(page).toContain('Nothing executed — proposal pending')
    expect(page).not.toContain('Action executed atomically')
    expect(page).toContain('does not prove delivery to product projections')
    expect(page).toContain('Retry')
  })

  it('contains no mutation call or approval control', () => {
    expect(page).not.toContain("method: 'POST'")
    expect(page).not.toContain('/decision')
    expect(page).not.toContain('Approve proposal')
    expect(page).not.toContain('Reject proposal')
  })
})
