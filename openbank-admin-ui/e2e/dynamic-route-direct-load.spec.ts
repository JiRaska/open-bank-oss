// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { ROLES } from '../src/lib/auth/roles'
import { signInWithRoles } from './helpers/auth'
import { waitForSettledShell } from './helpers/shell'
import { DYNAMIC_ROUTE_FIXTURES } from './helpers/dynamic-route-inventory'
import { DYNAMIC_ROUTE_CLASSIFICATIONS, readAppPageRoutes } from './helpers/navigation-inventory'

const fixtures = Object.entries(DYNAMIC_ROUTE_FIXTURES).sort(([a], [b]) => a.localeCompare(b))
const allNavigationRoles = Object.values(ROLES).filter(role => role !== ROLES.DEMO && role !== ROLES.API)
const unavailableState = /nothing found|not found|not available|unavailable|not deployed|not responding|unreachable|failed to load|no data yet|could not load|unable to load|nelze načíst|nic nenalezeno|nedostup|není nasazen|neodpovídá|načtení selhalo|zatím žádná data/i
const expectedTerminalText: Record<string, RegExp> = {
  '/r/[token]': /Invalid or expired invitation link/,
  '/docs/adr/[slug]': /ADR-0145|Decision not found/i,
  '/docs/changelog/[service]': /Changelog|No changelog has been published/i,
  '/docs/release-notes/[service]': /Release Notes|No releases for this service yet/i,
  '/docs/threat-models/[service]': /Threat model|No threat model has been published/i,
  '/services/[name]/docs/[[...slug]]': /01-overview|Architecture|No documents/i,
  '/accounts/[id]': /Nothing found/,
  '/cards/[id]': /Nothing found|Failed to load|not available/i,
  '/iaops/agents/[agentId]': /Nothing found/,
  '/iaops/cases/[caseId]': /Nothing found/,
  '/lending/applications/[id]': /lending-service is unreachable/i,
}

function isLocalTelemetryPost(request: import('@playwright/test').Request, expectedOrigin: string): boolean {
  const url = new URL(request.url())
  return url.origin === expectedOrigin
    && url.pathname === '/api/telemetry/traces'
    && url.search === ''
    && request.method() === 'POST'
}

function routeTemplatePattern(template: string): RegExp {
  const segments = template.split('/').filter(Boolean)
  const pattern = segments.map(segment => {
    if (segment.startsWith('[[...')) return '(?:[^/]+(?:/[^/]+)*)?'
    if (segment.startsWith('[')) return '[^/]+'
    return segment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  }).join('/')
  return new RegExp(`^/${pattern}/?$`)
}

test.describe('read-only direct loads for dynamic pages', () => {
  test.setTimeout(60_000)

  test('every dynamic template has one representative safe GET fixture', () => {
    const routes = readAppPageRoutes().filter(route => route.includes('[')).sort()
    expect(Object.keys(DYNAMIC_ROUTE_FIXTURES).sort()).toEqual(routes)
    expect(Object.keys(DYNAMIC_ROUTE_CLASSIFICATIONS).sort()).toEqual(routes)
    for (const [template, fixture] of fixtures) {
      const fixtureUrl = new URL(fixture.path, 'http://127.0.0.1')
      expect(fixtureUrl.origin, `${template} must stay on the local origin`).toBe('http://127.0.0.1')
      expect(fixtureUrl.pathname.startsWith('//'), `${template} must not be protocol-relative`).toBe(false)
      expect(fixtureUrl.pathname, `${template} must match its route template`).toMatch(routeTemplatePattern(template))
      expect(fixtureUrl.pathname, `${template} should resolve a concrete path`).not.toContain('[')
      expect(fixture.evidence, `${template} needs source or fixture evidence`).not.toBe('')
    }
  })

  for (const [template, fixture] of fixtures) {
    test(`${template} resolves for ${fixture.kind} (${fixture.evidence})`, async ({ page, context, baseURL }) => {
      // The recovery-token page is an unauthenticated customer entry; all other dynamic
      // operator routes use the local fixture role set.
      if (template !== '/r/[token]') await signInWithRoles(context, baseURL!, allNavigationRoles)

      const pageErrors: string[] = []
      const writeRequests: string[] = []
      page.on('pageerror', error => pageErrors.push(error.message))
      const expectedOrigin = new URL(baseURL!).origin
      page.on('request', request => {
        const url = new URL(request.url())
        if (url.origin === expectedOrigin && !['GET', 'HEAD', 'OPTIONS'].includes(request.method()) && !isLocalTelemetryPost(request, expectedOrigin)) {
          writeRequests.push(`${request.method()} ${url.pathname}`)
        }
      })

      // Keep the smoke deterministic and read-only: browser API reads resolve as 404,
      // exercising the page's settled failure/unavailable state. This does not assert
      // that a backend record is absent. Populated-state behavior is covered only by
      // the existing specs explicitly marked in the fixture inventory.
      await page.route('**/api/**', route => {
        const request = route.request()
        const url = new URL(request.url())
        if (url.pathname.startsWith('/api/auth/')) return route.continue()
        // The app's same-origin RUM POST is expected background telemetry. Fulfill it locally;
        // this exact endpoint is the only write exception, and no telemetry leaves the test.
        if (isLocalTelemetryPost(request, expectedOrigin)) return route.fulfill({ status: 204 })
        if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
          return route.fulfill({ status: 405, contentType: 'application/json', body: '{"error":"read_only_smoke"}' })
        }
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"fixture_not_found"}' })
      })

      const response = await page.goto(fixture.path, { waitUntil: 'domcontentloaded' })
      expect(response, `${template} document response`).not.toBeNull()
      expect(response!.status(), `${template} document status`).toBeLessThan(400)
      await expect.poll(() => new URL(page.url()).pathname).toBe(new URL(fixture.path, baseURL).pathname)
      if (template !== '/r/[token]') await waitForSettledShell(page)

      const main = page.locator(template === '/r/[token]' ? 'main' : '#main-content')
      await expect(main, `${template} shell content`).toBeVisible()
      const terminalText = expectedTerminalText[template] ?? unavailableState
      await expect.poll(async () => {
        const content = (await main.innerText()).trim()
        const isDocument = fixture.kind === 'repository-document'
        const loading = /\bloading\b|načít(?:ám|ání|á se)|probíhá načítání/i.test(content)
        return content.length > 20 && (isDocument || !loading) && terminalText.test(content)
      }, { message: `${template} should reach its declared terminal UI state`, timeout: 12_000 }).toBe(true)
      await expect(page.getByRole('alert').filter({
        hasText: /This screen failed to render|Tuto obrazovku se nepodařilo zobrazit/i,
      }), `${template} render boundary`).toHaveCount(0)
      await expect(main.getByRole('heading', {
        name: /^(404|page not found|this page could not be found\.?|stránka nebyla nalezena)$/i,
      }), `${template} framework not-found`).toHaveCount(0)
      expect(pageErrors, `${template} uncaught browser errors`).toEqual([])
      expect(writeRequests, `${template} must remain read-only`).toEqual([])
    })
  }
})
