// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0344 workbench: every new page is gated by AuthGuard AND the edge route table, no staff role
// outside the treasury desk can open or write anything in it, each write is granted to exactly the
// role the contract names (never ADMIN), and the BFF relays the caller's own bearer, refuses a
// session-less call, and passes a backend 403/422 through unchanged for every new write.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { NextRequest } from 'next/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { hasPermission, permissionForPath, ROLES, type Permission } from '@/lib/auth/roles'
import { personaForRoles, workspaceFor } from '@/lib/auth/persona'
import { TREASURY_AREAS } from '@/components/treasury/TreasuryAreaNav'

const auth = vi.fn()
vi.mock('@/auth', () => ({ auth }))
vi.mock('@/lib/discovery', () => ({ inCluster: () => false, discoverServices: vi.fn() }))

const root = resolve(__dirname, '..')
const read = (p: string) => readFileSync(resolve(root, p), 'utf8')
const sidebar = read('components/layout/Sidebar.tsx')

const PAGES: [string, string][] = [
  ['app/treasury/calendar/page.tsx', '/treasury/calendar'],
  ['app/treasury/analysis/cash-flows/page.tsx', '/treasury/analysis/cash-flows'],
  ['app/treasury/fixings/page.tsx', '/treasury/fixings'],
  ['app/treasury/market-data/page.tsx', '/treasury/market-data'],
  ['app/treasury/payments/page.tsx', '/treasury/payments'],
  ['app/treasury/reference/page.tsx', '/treasury/reference'],
  ['app/treasury/reports/page.tsx', '/treasury/reports'],
  ['app/treasury/day-completion/page.tsx', '/treasury/day-completion'],
]

const OUTSIDE_THE_DESK = [
  ROLES.OPERATOR, ROLES.VIEWER, ROLES.COMPLIANCE, ROLES.PAYMENTS, ROLES.AUDITOR, ROLES.SUPERVISOR,
  ROLES.CREDIT_RISK, ROLES.LENDING_OFFICER, ROLES.RISK, ROLES.FINANCE,
]
const DESK = [
  ROLES.TREASURY_DEALER, ROLES.TREASURY_APPROVER, ROLES.TREASURY_SENIOR_APPROVER, ROLES.TREASURY_BACK_OFFICE,
  ROLES.TREASURY_REFERENCE_EDITOR, ROLES.TREASURY_PAYMENT_RELEASER,
]
const granted = (p: Permission) => Object.values(ROLES).filter(r => hasPermission([r], p)).sort()

describe('workbench pages — route and page gates', () => {
  it.each(PAGES)('%s is gated by AuthGuard and by the edge route table (%s)', (file, path) => {
    expect(read(file)).toContain('<AuthGuard permission="treasury:workbench:view">')
    expect(permissionForPath(path)).toBe('treasury:workbench:view')
  })

  it.each(PAGES)('%s is denied to every staff role outside the treasury desk, and to no roles at all', (_file, path) => {
    const permission = permissionForPath(path)!
    for (const role of OUTSIDE_THE_DESK) expect(hasPermission([role], permission), `${role} -> ${path}`).toBe(false)
    expect(hasPermission([], permission)).toBe(false)
  })

  it('every desk role and ADMIN may read the workbench', () => {
    for (const role of [...DESK, ROLES.ADMIN]) expect(hasPermission([role], 'treasury:workbench:view'), role).toBe(true)
  })

  it('the deal pages stay gated as before (view / create), and the new roles read the deal pages the contract admits them to', () => {
    expect(permissionForPath('/treasury/deals/new')).toBe('treasury:deal:create')
    expect(permissionForPath('/treasury/deals/0b7c')).toBe('treasury:view')
    expect(hasPermission([ROLES.TREASURY_BACK_OFFICE], 'treasury:view')).toBe(true)
    expect(hasPermission([ROLES.TREASURY_PAYMENT_RELEASER], 'treasury:view')).toBe(false)
    expect(hasPermission([ROLES.TREASURY_BACK_OFFICE], 'treasury:deal:create')).toBe(false)
  })

  it('nav entries and area links carry the same permission as the page they open', () => {
    for (const [, path] of PAGES) {
      expect(sidebar).toMatch(new RegExp(`href: '${path}',\\s+icon: \\w+,\\s+permission: 'treasury:workbench:view'`))
    }
    for (const link of TREASURY_AREAS.flatMap(a => a.items)) {
      expect(permissionForPath(link.href), link.href).toBe(link.permission)
    }
  })

  it('every new desk role lands in the treasury persona, and its shortcuts stay inside its grant', () => {
    for (const role of DESK) {
      expect(personaForRoles([role]), role).toBe('treasury')
      expect(hasPermission([role], 'dashboard:view'), role).toBe(true)
    }
    for (const link of workspaceFor('treasury')) expect(permissionForPath(link.href)).toBe(link.permission)
  })
})

describe('workbench writes — exactly the contract’s roles, never ADMIN', () => {
  const WRITES: [Permission, string[]][] = [
    ['treasury:backoffice:write', [ROLES.TREASURY_BACK_OFFICE]],
    ['treasury:confirmation:send', [ROLES.TREASURY_BACK_OFFICE, ROLES.TREASURY_APPROVER]],
    ['treasury:decision:approve', [ROLES.TREASURY_APPROVER, ROLES.TREASURY_SENIOR_APPROVER]],
    ['treasury:reference:propose', [ROLES.TREASURY_REFERENCE_EDITOR, ROLES.TREASURY_BACK_OFFICE]],
    ['treasury:reference:approve', [ROLES.TREASURY_APPROVER, ROLES.TREASURY_SENIOR_APPROVER]],
    ['treasury:payment:release', [ROLES.TREASURY_PAYMENT_RELEASER]],
  ]

  it.each(WRITES)('%s is granted to exactly its roles', (permission, roles) => {
    expect(granted(permission)).toEqual([...roles].sort())
  })

  it.each(WRITES)('%s is denied to ADMIN and to every role outside the desk (a forbidden role is a 403 at the service)', permission => {
    expect(hasPermission([ROLES.ADMIN], permission)).toBe(false)
    for (const role of OUTSIDE_THE_DESK) expect(hasPermission([role], permission), role).toBe(false)
  })

  it('the dealer who creates a deal can neither release its payment nor decide reference data or fixings', () => {
    const dealer = [ROLES.TREASURY_DEALER]
    for (const p of ['treasury:payment:release', 'treasury:reference:approve', 'treasury:decision:approve', 'treasury:backoffice:write'] as Permission[]) {
      expect(hasPermission(dealer, p), p).toBe(false)
    }
  })

  it('the payment releaser does nothing but release: no deal, reference or approval right', () => {
    const releaser = [ROLES.TREASURY_PAYMENT_RELEASER]
    for (const p of ['treasury:deal:create', 'treasury:deal:approve', 'treasury:reference:propose', 'treasury:reference:approve', 'treasury:backoffice:write', 'treasury:decision:approve'] as Permission[]) {
      expect(hasPermission(releaser, p), p).toBe(false)
    }
  })

  it('the page code gates every control on a permission, not on a hard-coded role name', () => {
    for (const [file] of [...PAGES, ['components/treasury/wb/DealWorkbench.tsx', ''], ['components/treasury/wb/ReferenceWorkbench.tsx', '']] as [string, string][]) {
      expect(read(file), file).not.toMatch(/includes\('ROLE_TREASURY/)
    }
  })
})

// ── the BFF relay for the new writes ───────────────────────────────────────────────────────────
const ctx = (path: string[]) => ({ params: Promise.resolve({ service: 'treasury-service', path }) })
const TREASURY = ['api', 'v1', 'treasury']
const route = () => import('@/app/api/svc/[service]/[...path]/route')

const NEW_WRITES: [string, string[]][] = [
  ['release a payment', [...TREASURY, 'payments', 'p-1', 'release']],
  ['approve a reference version', [...TREASURY, 'reference', 'calendar', 'CERTIS', 'versions', '2', 'approve']],
  ['propose a cash-flow override', [...TREASURY, 'deals', 'd-1', 'cash-flows', 'f-1', 'overrides']],
  ['approve a cash-flow override', [...TREASURY, 'deals', 'd-1', 'cash-flows', 'f-1', 'overrides', 'o-1', 'approve']],
  ['approve a manual fixing', [...TREASURY, 'fixings', 'PRIBOR_3M', '2026-10-19', 'manual', 'approve']],
  ['run fixings', [...TREASURY, 'fixings', 'run']],
  ['send a confirmation', [...TREASURY, 'deals', 'd-1', 'send-confirmation']],
  ['close the day', [...TREASURY, 'positions', 'close-of-day']],
]

describe('treasury BFF — the new workbench writes', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
    vi.unstubAllGlobals()
    delete process.env.SERVICES_HOST
  })

  it.each(NEW_WRITES)('%s: a session-less call is refused with 401 and never reaches treasury-service', async (_n, path) => {
    auth.mockResolvedValue(null)
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const { POST } = await route()
    const res = await POST(new NextRequest(`http://localhost/api/svc/treasury-service/${path.join('/')}`, { method: 'POST' }), ctx(path))
    expect(res.status).toBe(401)
    expect(fetch).not.toHaveBeenCalled()
  })

  it.each(NEW_WRITES)('%s: relays the caller’s own bearer and Idempotency-Key, and passes a forbidden role’s 403 through', async (_n, path) => {
    auth.mockResolvedValue({ user: { accessToken: 'viewer-token', email: 'viewer@example.test' } })
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'forbidden' }), { status: 403, headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', fetch)
    const { POST } = await route()
    const req = new NextRequest(`http://localhost/api/svc/treasury-service/${path.join('/')}`, { method: 'POST', headers: { 'idempotency-key': 'intent-1' } })
    const res = await POST(req, ctx(path))
    expect(res.status).toBe(403)
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(`http://localhost:8160/${path.join('/')}`)
    expect(new Headers(init.headers).get('authorization')).toBe('Bearer viewer-token')
    expect(new Headers(init.headers).get('idempotency-key')).toBe('intent-1')
  })

  it('passes the service’s 422 envelopes (field-level data included) through verbatim', async () => {
    auth.mockResolvedValue({ user: { accessToken: 'dealer-token' } })
    const body = JSON.stringify({ error: 'REFERENCE_NOT_EFFECTIVE', missingReferences: ['book:BANKING'], breaches: [{ rule: 'MAX_TENOR', limit: '90', actual: '120' }] })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 422, headers: { 'content-type': 'application/json' } })))
    const { POST } = await route()
    const path = [...TREASURY, 'deals', 'd-1', 'schedule-preview']
    const res = await POST(new NextRequest(`http://localhost/api/svc/treasury-service/${path.join('/')}`, { method: 'POST' }), ctx(path))
    expect(res.status).toBe(422)
    expect(await res.json()).toEqual(JSON.parse(body))
  })

  it('relays risk-engine reads with the caller’s bearer too (the report pages)', async () => {
    auth.mockResolvedValue({ user: { accessToken: 'risk-token' } })
    const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', fetch)
    const { GET } = await route()
    const res = await GET(new NextRequest('http://localhost/api/svc/risk-engine/api/v1/risk/snapshots/r-1/var?confidence=0.99'), { params: Promise.resolve({ service: 'risk-engine', path: ['api', 'v1', 'risk', 'snapshots', 'r-1', 'var'] }) })
    expect(res.status).toBe(200)
    expect(new Headers((fetch.mock.calls[0] as [string, RequestInit])[1].headers).get('authorization')).toBe('Bearer risk-token')
  })
})
