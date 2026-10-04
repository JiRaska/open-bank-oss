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
import { parseAllDocuments } from 'yaml'
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

// ADR-0324 Phase 2 — the same gate, answered in Envoy Gateway's ext_authz contract.
// Envoy appends the original request path to the SecurityPolicy's `extAuth.http.path`
// (`/api/gate/<tool>`), treats ONLY a 200 as allow, and forwards a denial's status and
// headers to the browser verbatim. So: 200 / 302-to-login / 403, never 204.
async function envoyRoute() {
  return import('@/app/api/gate/[tool]/[[...uri]]/route')
}
const envoyReq = (path: string, method = 'GET') =>
  new NextRequest(`http://admin.open-bank.tech${path}`, { method })
const ctx = (tool: string) => ({ params: Promise.resolve({ tool }) })

describe('/api/gate/<tool>/<uri> — Envoy ext_authz contract', () => {
  beforeEach(() => vi.resetModules())
  afterEach(() => vi.restoreAllMocks())

  it('answers 200, not 204, for an entitled operator', async () => {
    // Envoy's HTTP ext_authz treats every status but 200 as a DENIAL and relays it to
    // the client: a 204 here reached the browser as an empty 204 and the tool was never
    // called (measured against envoy v1.39.1, the proxy Envoy Gateway v1.9.1 pins).
    vi.mocked(auth).mockResolvedValue(session(['ROLE_OPERATOR']))
    const res = await (await envoyRoute()).GET(envoyReq('/api/gate/grafana/tools/grafana/d/x'), ctx('grafana'))
    expect(res.status).toBe(200)
    expect(res.headers.get('location')).toBeNull()
  })

  it('302s an unauthenticated request to login, carrying the deep link', async () => {
    // The auth-signin equivalent: Envoy relays this Location to the browser.
    vi.mocked(auth).mockResolvedValue(null as never)
    const res = await (await envoyRoute()).GET(
      envoyReq('/api/gate/grafana/tools/grafana/d/abc?orgId=1&from=now-1h'), ctx('grafana'))
    expect(res.status).toBe(302)
    expect(res.headers.get('location')).toBe(
      '/auth/login?callbackUrl=' + encodeURIComponent('/tools/grafana/d/abc?orgId=1&from=now-1h'))
    expect(res.headers.get('cache-control')).toBe('no-store')
  })

  it('302s when the Keycloak refresh failed or the session decode throws — fail closed', async () => {
    vi.mocked(auth).mockResolvedValue(session(['ROLE_ADMIN'], 'RefreshAccessTokenError'))
    expect((await (await envoyRoute()).GET(envoyReq('/api/gate/grafana/tools/grafana/'), ctx('grafana'))).status).toBe(302)
    vi.mocked(auth).mockRejectedValue(new Error('jwt decrypt failed'))
    expect((await (await envoyRoute()).GET(envoyReq('/api/gate/grafana/tools/grafana/'), ctx('grafana'))).status).toBe(302)
  })

  it.each([
    ['another tool', '/api/gate/grafana/tools/alertmanager/'],
    ['the console', '/api/gate/grafana/dashboard'],
    ['a protocol-relative URL', '/api/gate/grafana//evil.example/x'],
    ['a prefix look-alike', '/api/gate/grafana/tools/grafanax'],
  ])('never turns the deep link into a redirect to %s', async (_, path) => {
    // The URI arrives from the browser; it may only send the operator back to the tool
    // the policy named.
    vi.mocked(auth).mockResolvedValue(null as never)
    const res = await (await envoyRoute()).GET(envoyReq(path), ctx('grafana'))
    expect(res.status).toBe(302)
    expect(res.headers.get('location')).toBe('/auth/login')
  })

  it('403s the demo account on alertmanager and an unknown tool — never a login loop', async () => {
    vi.mocked(auth).mockResolvedValue(session(['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_DEMO']))
    const am = await (await envoyRoute()).GET(envoyReq('/api/gate/alertmanager/tools/alertmanager/'), ctx('alertmanager'))
    expect(am.status).toBe(403)
    vi.mocked(auth).mockResolvedValue(session(['ROLE_ADMIN']))
    const unknown = await (await envoyRoute()).GET(envoyReq('/api/gate/glitchtip/tools/glitchtip/'), ctx('glitchtip'))
    expect(unknown.status).toBe(403)
    // An inherited Object property is not a tool.
    const proto = await (await envoyRoute()).GET(envoyReq('/api/gate/constructor/x'), ctx('constructor'))
    expect(proto.status).toBe(403)
  })

  it('answers every method — Envoy checks with the ORIGINAL method', async () => {
    // Grafana and Alertmanager POST/PUT/DELETE through the same route; a missing handler
    // would be a 405 denial on every write.
    const mod = await envoyRoute()
    vi.mocked(auth).mockResolvedValue(session(['ROLE_OPERATOR']))
    for (const m of ['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'] as const) {
      const res = await mod[m](envoyReq('/api/gate/grafana/tools/grafana/api/x', m), ctx('grafana'))
      expect(res.status, m).toBe(200)
    }
  })

  it('agrees with the nginx contract on every decision', async () => {
    // One decision, two spellings: allow 204|200, unauthenticated 401|302, forbidden 403|403.
    const spell: Record<number, number> = { 204: 200, 401: 302, 403: 403 }
    const cases: [string, string[] | null][] = [
      ['grafana', ['ROLE_ADMIN']], ['grafana', ['ROLE_VIEWER']], ['grafana', null],
      ['alertmanager', ['ROLE_ADMIN', 'ROLE_DEMO']], ['pyrra', ['ROLE_OPERATOR']], ['nope', ['ROLE_ADMIN']],
    ]
    for (const [tool, roles] of cases) {
      vi.mocked(auth).mockResolvedValue(roles ? session(roles) : (null as never))
      const nginx = (await (await route()).GET(req(`?tool=${tool}`))).status
      const envoy = (await (await envoyRoute()).GET(envoyReq(`/api/gate/${tool}/tools/${tool}/`), ctx(tool))).status
      expect(envoy, `${tool} / ${roles}`).toBe(spell[nginx])
    }
  })
})

describe('ADR-0234 wiring — the halves of the boundary agree', () => {
  // The allow-list lives in the shared decision module both edge contracts call.
  const routeSrc = readFileSync('src/lib/auth/toolGate.ts', 'utf8')
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

  // ADR-0324 Phase 2: the same boundary on the Envoy Gateway. The SecurityPolicy's
  // `extAuth.http.path` is what names the tool there, so it has to agree with the gate and
  // the Ingress exactly as the Ingress's `?tool=` does.
  describe('the Envoy Gateway SecurityPolicies (httproute-tools-gate.yaml)', () => {
    const docs = parseAllDocuments(
      readFileSync('../openbank-infra/gitops/components/observability/httproute-tools-gate.yaml', 'utf8'),
    ).map(d => d.toJS()).filter(Boolean)
    const policies = docs.filter(d => d.kind === 'SecurityPolicy')
    const routes = docs.filter(d => d.kind === 'HTTPRoute')

    it('declares one policy and one route per Ingress tool', () => {
      // Guards the per-policy assertions below from passing over an empty list.
      expect(policies.length).toBe(ingressTools.length)
      expect(routes.length).toBe(ingressTools.length)
    })

    it.each(ingressTools)('%s: its route is gated by a policy naming the same tool', tool => {
      const route = routes.find(r =>
        r.spec.rules.some((rule: { matches?: { path?: { value?: string } }[] }) =>
          (rule.matches ?? []).some(m => m.path?.value === `/tools/${tool}`)))
      expect(route, `no HTTPRoute for /tools/${tool}`).toBeDefined()
      const policy = policies.find(p =>
        p.spec.targetRefs.some((t: { kind: string; name: string }) =>
          t.kind === 'HTTPRoute' && t.name === route.metadata.name))
      expect(policy, `no SecurityPolicy targets ${route.metadata.name}`).toBeDefined()
      expect(policy.spec.extAuth.http.path).toBe(`/api/gate/${tool}`)
      expect(gateTools).toContain(tool)
    })

    it.each(policies.map(p => [p.metadata.name, p]))(
      '%s forwards the session cookie, fails closed and copies nothing to the backend',
      (_, p) => {
        const ext = p.spec.extAuth
        expect(ext.headersToExtAuth.map((h: string) => h.toLowerCase())).toContain('cookie')
        expect(ext.failOpen).not.toBe(true)
        // The identity-header trust model ADR-0234 rejects, as auth-response-headers on nginx.
        expect(ext.http.headersToBackend).toBeUndefined()
        expect(ext.http.backendRefs).toEqual([{ name: 'admin-ui', namespace: 'admin-ui', port: 3000 }])
      },
    )

    it('no route rewrites the sub-path the tools are configured to serve', () => {
      expect(JSON.stringify(routes)).not.toMatch(/URLRewrite/)
    })
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
