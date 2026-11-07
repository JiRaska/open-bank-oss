// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { ROLES } from '../src/lib/auth/roles'
import { signInWithRoles } from './helpers/auth'
import { waitForSettledShell } from './helpers/shell'
import { STATIC_ROUTE_INVENTORY } from './helpers/static-route-inventory'

const fixtures = Object.entries(STATIC_ROUTE_INVENTORY)
  .filter(([, fixture]) => fixture.disposition !== 'sidebar-navigation-smoke')
  .map(([route, fixture]) => ({ route, ...fixture }))
  .sort((left, right) => left.route.localeCompare(right.route))

const publicRoutes = new Set(['/auth/error', '/auth/forbidden', '/auth/login', '/privacy'])
const routeTitles: Record<string, RegExp> = {
  '/auth/error': /We could not sign you in|Přihlášení se nepodařilo/i,
  '/auth/forbidden': /This area is not in your role|Tato oblast není součástí vaší role/i,
  '/auth/login': /Explore operations with confidence|Prozkoumejte provoz s jistotou/i,
  '/privacy': /Know what happens to your operator data|Víte, co se děje s údaji operátora/i,
}
const allNavigationRoles = Object.values(ROLES).filter(role => role !== ROLES.DEMO && role !== ROLES.API)

test.describe('read-only direct loads for static pages outside the sidebar', () => {
  test.setTimeout(6 * 60 * 1000)

  for (const fixture of fixtures) {
    test(`${fixture.route} renders from a safe direct GET`, async ({ page, context, baseURL }) => {
      const isPublic = publicRoutes.has(fixture.route)
      if (!isPublic) await signInWithRoles(context, baseURL!, allNavigationRoles)

      const pageErrors: string[] = []
      const writeRequests: string[] = []
      const feedbackRequests: string[] = []
      page.on('pageerror', error => pageErrors.push(error.message))
      page.on('request', request => {
        const url = new URL(request.url())
        if (fixture.route === '/feedback' && url.pathname.includes('/api/feedback/')) {
          feedbackRequests.push(`${request.method()} ${url.pathname}`)
        }
        if (url.origin === new URL(baseURL!).origin && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
          writeRequests.push(`${request.method()} ${url.pathname}`)
        }
      })
      // Observe and block same-origin writes before a page effect can reach a business API.
      // The assertion below still fails the smoke if a route attempts one; it is never sent.
      await page.route('**/*', route => {
        const request = route.request()
        const url = new URL(request.url())
        if (url.origin === new URL(baseURL!).origin && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
          return route.fulfill({ status: 405, contentType: 'application/json', body: '{"error":"read_only_direct_load_smoke"}' })
        }
        return route.continue()
      })
      if (fixture.route === '/feedback') {
        await page.route('**/api/feedback/screen-feedback**', route =>
          route.fulfill({ status: 404, contentType: 'application/json', body: '{"available":false,"error":"fixture_not_found"}' }),
        )
      }

      const suffix = fixture.route === '/auth/error' ? '?error=AccessDenied'
        : fixture.route === '/auth/forbidden' ? '?path=%2Fapprovals%2Fcommunication'
          : ''
      const response = await page.goto(`${fixture.path}${suffix}`, { waitUntil: 'domcontentloaded' })
      expect(response, `${fixture.route} should return a document response`).not.toBeNull()
      expect(response!.status(), `${fixture.route} document status`).toBeLessThan(400)

      const expectedPath = fixture.route === '/' ? '/dashboard' : fixture.route
      await expect.poll(() => new URL(page.url()).pathname, {
        message: `${fixture.route} expected destination ${expectedPath}`,
        timeout: 12_000,
      }).toBe(expectedPath)

      if (!isPublic) await waitForSettledShell(page)

      const main = page.locator(isPublic ? 'main' : '#main-content').first()
      await expect(main, `${fixture.route} main content`).toBeVisible()
      if (fixture.route === '/feedback') {
        await expect(main.getByText(/^(Loading…|Načítám…)$/), `feedback API ${feedbackRequests.join(', ')}`).toBeHidden()
        await expect(main.getByRole('heading', { level: 1, name: /Screen feedback|Zpětná vazba k obrazovkám/i })).toBeVisible()
        await expect(main.getByText(/ClickHouse is not responding|ClickHouse neodpovídá/i)).toBeVisible()
      }
      const h1 = main.getByRole('heading', { level: 1 }).first()
      if (isPublic) {
        await expect(h1).toBeVisible()
        await expect(h1).toContainText(routeTitles[fixture.route])
      } else if (await h1.count()) {
        await expect(h1).toBeVisible()
        expect((await h1.innerText()).trim(), `${fixture.route} primary heading`).not.toBe('')
      } else {
        expect((await main.innerText()).trim().length, `${fixture.route} meaningful content`).toBeGreaterThan(15)
      }

      await expect(page.getByRole('alert').filter({
        hasText: /This screen failed to render|Tuto obrazovku se nepodařilo zobrazit/i,
      }), `${fixture.route} render error boundary`).toHaveCount(0)
      await expect(main.getByRole('heading', {
        name: /^(404|page not found|this page could not be found\.?|stránka nebyla nalezena)$/i,
      }), `${fixture.route} not-found page`).toHaveCount(0)
      expect(pageErrors, `${fixture.route} uncaught browser errors`).toEqual([])
      expect(writeRequests, `${fixture.route} must remain read-only`).toEqual([])
    })
  }
})
