// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0234 — the identity-aware edge gate.
//
// This route is not called by a browser: nginx calls it as an `auth_request`
// sub-request and reads ONLY the status code. That makes the status contract the
// entire security boundary, and it fails in a direction no page test would
// notice — nginx maps anything that is not 2xx/401/403 to a 500, so a redirect
// or a thrown error turns "deny" into "the dashboard is down", and a stray 200
// on an unknown tool turns the allow-list into a pass-through.
//
// The file-level assertions at the bottom exist because the two halves of the
// boundary live in different repos-worth of file types: the Ingress hard-codes
// `?tool=` and the route owns the allow-list keyed by it. Nothing else compares
// them, so a path added to one and not the other would only be discovered by
// trying it.

import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'
import { PERMISSIONS } from '@/lib/auth/roles'

async function route() {
  return import('@/app/api/gate/route')
}

const req = (qs: string) => new NextRequest(`http://localhost/api/gate${qs}`)

const session = (roles: string[], error?: string) =>
  ({ user: { accessToken: 't', roles, error } }) as never

describe('GET /api/gate — nginx auth_request contract', () => {
  beforeEach(() => vi.resetModules())
  afterEach(() => vi.restoreAllMocks())

  it('204s an operator for a known tool', async () => {
    vi.mocked(auth).mockResolvedValue(session(['ROLE_OPERATOR']))
    const res = await (await route()).GET(req('?tool=grafana'))
    expect(res.status).toBe(204)
    // A body on a 204 is a protocol error, and nginx would be reading it for nothing.
    expect(await res.text()).toBe('')
  })

  it('204s an admin for a known tool', async () => {
    vi.mocked(auth).mockResolvedValue(session(['ROLE_ADMIN']))
    expect((await (await route()).GET(req('?tool=grafana'))).status).toBe(204)
  })

  it('401s with no session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    expect((await (await route()).GET(req('?tool=grafana'))).status).toBe(401)
  })

  it('401s when the Keycloak refresh has failed', async () => {
    vi.mocked(auth).mockResolvedValue(session(['ROLE_ADMIN'], 'RefreshAccessTokenError'))
    expect((await (await route()).GET(req('?tool=grafana'))).status).toBe(401)
  })

  it('401s — not 500 — when the session decode throws', async () => {
    // Fail CLOSED. A thrown session must not become an nginx 500 that reads as
    // "the tool is broken", and must never fall through to an allow.
    vi.mocked(auth).mockRejectedValue(new Error('jwt decrypt failed'))
    expect((await (await route()).GET(req('?tool=grafana'))).status).toBe(401)
  })

  it('403s an authenticated user without the permission', async () => {
    vi.mocked(auth).mockResolvedValue(session(['ROLE_VIEWER']))
    expect((await (await route()).GET(req('?tool=grafana'))).status).toBe(403)
  })

  it('403s the public demo account on alertmanager despite ROLE_ADMIN', async () => {
    // Alertmanager has no role model: its UI is its API, so anything that can load
    // the page can silence or expire alerts platform-wide. There is no read-only
    // Alertmanager to offer a public account, so it is denied outright.
    vi.mocked(auth).mockResolvedValue(session(['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_DEMO']))
    expect((await (await route()).GET(req('?tool=alertmanager'))).status).toBe(403)
  })

  it.each(['grafana', 'pyrra'])(
    'still ADMITS the demo account to %s — a greyed-out console is the worse outcome',
    async tool => {
      // Deliberate: the demo exists so a visitor sees a working platform. It is held
      // to least privilege INSIDE each tool instead — Grafana pins ROLE_DEMO to
      // Viewer (no Explore, so no raw Loki/Tempo), Pyrra is read-only by construction.
      // If this ever flips to 403, the demo silently starts looking broken.
      vi.mocked(auth).mockResolvedValue(session(['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_DEMO']))
      expect((await (await route()).GET(req(`?tool=${tool}`))).status).toBe(204)
    },
  )

  it('403s an unknown tool even for an admin', async () => {
    // The allow-list is what makes an Ingress path added without a gate entry
    // fail closed. If this ever returns 204 the allow-list is decorative.
    vi.mocked(auth).mockResolvedValue(session(['ROLE_ADMIN']))
    expect((await (await route()).GET(req('?tool=glitchtip'))).status).toBe(403)
    expect((await (await route()).GET(req('?tool='))).status).toBe(403)
    expect((await (await route()).GET(req(''))).status).toBe(403)
  })

  it('never answers with a status nginx would map to 500', async () => {
    // nginx auth_request accepts 2xx, propagates 401/403, and turns EVERYTHING
    // else — including a 302 to the login page — into a 500.
    const cases: [string, string[] | null][] = [
      ['?tool=grafana', ['ROLE_ADMIN']],
      ['?tool=grafana', ['ROLE_VIEWER']],
      ['?tool=grafana', null],
      ['?tool=nope', ['ROLE_ADMIN']],
      ['', null],
    ]
    for (const [qs, roles] of cases) {
      vi.mocked(auth).mockResolvedValue(roles ? session(roles) : (null as never))
      const res = await (await route()).GET(req(qs))
      expect([204, 401, 403], `${qs} / ${roles}`).toContain(res.status)
      expect(res.headers.get('location'), `${qs} must not redirect`).toBeNull()
    }
  })
})

describe('ADR-0234 wiring — the halves of the boundary agree', () => {
  const routeSrc = readFileSync('src/app/api/gate/route.ts', 'utf8')
  const ingressSrc = readFileSync(
    '../openbank-infra/gitops/components/admin-ui/tools-gate.yaml', 'utf8',
  )
  const sidebarSrc = readFileSync('src/components/layout/Sidebar.tsx', 'utf8')

  // Every ?tool= the Ingress asks about must exist in the route's allow-list.
  // Case-INSENSITIVE character class on purpose. With `[a-z0-9-]+` a typo'd
  // `?tool=grafanaX` still captures the `grafana` prefix, so this assertion
  // passed against an Ingress that pointed at a tool the gate does not know —
  // caught only by feeding it that exact input.
  const ingressTools = [...ingressSrc.matchAll(/\/api\/gate\?tool=([A-Za-z0-9_-]+)/g)].map(m => m[1])
  const gateTools = [...routeSrc.matchAll(/^\s{2}([A-Za-z0-9_-]+):\s*"/gm)].map(m => m[1])

  it('the Ingress asks about at least one tool', () => {
    // Guards the two assertions below from passing vacuously if the regex or the
    // annotation format drifts.
    expect(ingressTools.length).toBeGreaterThan(0)
    expect(gateTools.length).toBeGreaterThan(0)
  })

  it.each(ingressTools)('the gate knows tool %s', tool => {
    expect(gateTools).toContain(tool)
  })

  it('the middleware matcher excludes /api/gate and pre-auth brand assets', () => {
    // Without this the middleware answers an unauthenticated sub-request with a
    // 302 and nginx turns the gate into a 500 — dashboards unreachable for
    // everyone, including the operators the gate would have admitted.
    //
    // Read the MATCHER, not the file: the exclusion is explained by a comment
    // three lines above it that also contains the string "api/gate", so a
    // whole-file grep stays green after the exclusion itself is deleted. That is
    // exactly what happened when this assertion was fed the deleted case.
    const matcher = readFileSync('src/proxy.ts', 'utf8')
      .replace(/\/\/.*$/gm, '')
      .match(/matcher:\s*\[([\s\S]*?)\]/)?.[1]
    expect(matcher, 'middleware config.matcher not found').toBeDefined()
    expect(matcher).toMatch(/api\/gate/)
    // The login page renders the Explorer from /public/brand before a session
    // exists. Matching /brand here redirects the image request back to login,
    // leaving the new experience deployed but the mascot invisible.
    expect(matcher).toMatch(/brand\//)
  })

  // Every tool, not just the first one. A gate wider than the nav hides access
  // nobody can find; a gate narrower than the nav renders a link that 403s. This
  // loops over the gate's own allow-list rather than naming tools, so adding a
  // tool without a Sidebar entry fails here instead of shipping.
  it.each(ingressTools)('the Sidebar link and the gate agree on the permission for %s', tool => {
    const navPerm = sidebarSrc.match(
      new RegExp(`href: '/tools/${tool}'[^}]*?permission: '([^']+)'`),
    )?.[1]
    const gatePerm = routeSrc.match(new RegExp(`\\b${tool}:\\s*"([^"]+)"`))?.[1]
    expect(navPerm, `no Sidebar entry for /tools/${tool}`).toBeDefined()
    expect(gatePerm, `no gate entry for ${tool}`).toBeDefined()
    expect(navPerm).toBe(gatePerm)
    expect(Object.keys(PERMISSIONS)).toContain(gatePerm)
  })

  it('every Sidebar tool link has a gate entry', () => {
    // The other direction: a nav link pointing at a /tools path the gate does not
    // know is a dead link — the gate denies an unknown tool with 403 by design.
    const navTools = [...sidebarSrc.matchAll(/href: '\/tools\/([A-Za-z0-9_-]+)'/g)].map(m => m[1])
    expect(navTools.length).toBeGreaterThan(0)
    expect([...new Set(navTools)].sort()).toEqual([...new Set(ingressTools)].sort())
  })

  it('Grafana pins ROLE_DEMO to Viewer, and tests it BEFORE the admin role', () => {
    // jmespath `||` short-circuits, so order IS the mechanism: placed after the
    // ROLE_ADMIN test this never fires and the public account lands as a Grafana
    // Admin with Explore over Prometheus, Loki and Tempo.
    const kps = readFileSync('../openbank-infra/gitops/apps/kube-prometheus-stack.yaml', 'utf8')
    const expr = kps.match(/role_attribute_path: (.*)/)?.[1]
    expect(expr, 'role_attribute_path not found').toBeDefined()
    expect(expr).toMatch(/ROLE_DEMO/)
    expect(expr!.indexOf('ROLE_DEMO')).toBeLessThan(expr!.indexOf('ROLE_ADMIN'))
    expect(expr).toMatch(/ROLE_DEMO'\)\s*&&\s*'Viewer'/)
  })

  it('the Ingress does not forward gate response headers into the upstream', () => {
    // ADR-0234 rejects the identity-header trust model outright: auth-response-headers
    // is how it would creep back in.
    expect(ingressSrc).not.toMatch(/^\s*nginx\.ingress\.kubernetes\.io\/auth-response-headers:/m)
  })

  it('the Ingress does not strip the sub-path Grafana is configured to serve', () => {
    // root_url carries /tools/grafana and serve_from_sub_path is true, so a
    // rewrite-target here would break every asset on the page.
    expect(ingressSrc).not.toMatch(/rewrite-target/)
  })

  it('grafana-tools is still the only ExternalName Service in gitops', () => {
    // `.trivyignore` carries AVD-KSV-0108 for this one Service, and a trivyignore
    // entry is REPO-WIDE — it would silently exempt the next ExternalName Service
    // anyone adds. KSV-0108 exists for CVE-2020-8554, where a Service pointing
    // OUTSIDE the cluster lets a namespace-scoped actor intercept traffic to an
    // arbitrary external IP; the suppression is only honest while every
    // ExternalName in the tree points in-cluster, as this one does.
    //
    // So assert the COUNT, not merely that ours exists. A second ExternalName
    // fails here and forces a decision instead of inheriting the exemption.
    const root = '../openbank-infra/gitops'
    const walk = (dir: string): string[] =>
      readdirSync(dir, { withFileTypes: true }).flatMap(e => {
        const p = join(dir, e.name)
        if (e.isDirectory()) return walk(p)
        return /\.ya?ml$/.test(e.name) ? [p] : []
      })

    const files = walk(root)
    // Guard against a vacuous pass if the path or the walk ever breaks.
    expect(files.length).toBeGreaterThan(100)

    const withExternalName = files.filter(f =>
      /^\s*type:\s*ExternalName\s*$/m.test(readFileSync(f, 'utf8')),
    )
    expect(withExternalName).toEqual([join(root, 'components/admin-ui/tools-gate.yaml')])
  })
})
