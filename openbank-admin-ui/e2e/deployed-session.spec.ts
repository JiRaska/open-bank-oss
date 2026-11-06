// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// AUTHENTICATED deployed smoke (#13171). Run by playwright.deployed-session.config.ts against the
// sandbox, after deployed-session.setup.ts has signed in through Keycloak as the least-privilege
// e2e operator (realm roles ROLE_VIEWER + ROLE_AUDITOR, no write role).
//
// READ-ONLY by construction: every non-GET request to the app's own origin (outside /api/auth/) is
// aborted and recorded, and each test asserts none was attempted. A smoke that could mutate the sandbox is not a smoke.
//
// The screen list is the auth-gate lane's list. Whether a screen is expected to RENDER or to send
// the operator to /auth/forbidden is derived from the same route guard the proxy enforces
// (src/lib/auth/roles.ts), so a screen this user cannot see is a least-privilege assertion, not a
// silent gap. A screen ROLE_OPERATOR can see is also opened as the synthetic operator.
import { expect, test, type Page } from '@playwright/test'
import { OPERATOR_STATE } from '../playwright.deployed-session.config'
import { pathAllowedByRoles } from '../src/lib/auth/roles'

const E2E_ROLES = ['ROLE_VIEWER', 'ROLE_AUDITOR']
const OPERATOR_ROLES = ['ROLE_OPERATOR']
// Same default as playwright.deployed-session.config.ts `baseURL`.
const APP_ORIGIN = new URL(process.env.ADMIN_UI_DEPLOYED_URL ?? 'https://admin.open-bank.tech').origin

const MONEY_PATH_SCREENS = [
  '/payments', '/sepa-instant', '/ledger', '/accounts', '/transactions', '/fx', '/clearing', '/swift',
  '/settlements', '/interest', '/sdd', '/standing-orders', '/treasury', '/cards', '/lending', '/fees',
  '/disputes', '/fraud', '/sanctions', '/pension', '/day-end', '/delegations', '/consents', '/approvals',
]

// Records what would make the screen a false pass: a write (aborted here) or a failing BFF call.
function watchTraffic(page: Page) {
  const writes: string[] = []
  const serverErrors: string[] = []
  void page.route('**/*', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    // Same origin only, so a Server Action POST to a page path counts as well as an /api route.
    // Auth and the browser trace export are the only writes a screen makes by itself; neither
    // reaches a bank backend, and neither does the cross-origin error-telemetry beacon.
    if (url.origin === APP_ORIGIN && req.method() !== 'GET' && req.method() !== 'HEAD'
        && !url.pathname.startsWith('/api/auth/') && url.pathname !== '/api/telemetry/traces') {
      writes.push(`${req.method()} ${url.pathname}`)
      await route.abort()
      return
    }
    await route.continue()
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.origin === APP_ORIGIN && url.pathname.startsWith('/api/') && response.status() >= 500) {
      serverErrors.push(`${response.status()} ${url.pathname}`)
    }
  })
  return { writes, serverErrors }
}

async function expectScreenRenders(page: Page, screen: string) {
  const { writes, serverErrors } = watchTraffic(page)
  const response = await page.goto(screen)
  expect(response?.status() ?? 0).toBeLessThan(500)
  expect(new URL(page.url()).pathname, 'redirected away from the screen').toBe(screen)

  const main = page.locator('main')
  await expect(main).toBeVisible()
  await expect(main.getByRole('heading').first()).toBeVisible()
  // Graceful-state rule: an undeployed backend renders DataUnavailable, never a raw failure.
  await expect(main).not.toContainText(/Application error|Internal Server Error|HTTP \d{3}/)
  await page.waitForLoadState('networkidle').catch(() => {})
  expect(serverErrors, 'the screen depends on a failing API').toEqual([])
  expect(writes, 'a read-only smoke attempted a write').toEqual([])
}

const id = (screen: string) => `E2E-${screen.slice(1)}`

for (const screen of MONEY_PATH_SCREENS) {
  if (pathAllowedByRoles(screen, E2E_ROLES)) {
    test(`session: ${screen} renders for the e2e operator @sandbox-session @verifies:admin-ui/${id(screen)}`, async ({ page }) => {
      await expectScreenRenders(page, screen)
    })
    continue
  }
  test(`session: ${screen} is forbidden to the least-privilege operator @sandbox-session @verifies:admin-ui/${id(screen)}`, async ({ page }) => {
    const { writes } = watchTraffic(page)
    await page.goto(screen)
    const url = new URL(page.url())
    expect(url.pathname).toBe('/auth/forbidden')
    expect(url.searchParams.get('path')).toBe(screen)
    expect(writes).toEqual([])
  })
  if (pathAllowedByRoles(screen, OPERATOR_ROLES)) {
    test.describe(() => {
      test.use({ storageState: OPERATOR_STATE })
      test(`session: ${screen} renders for the synthetic operator @sandbox-session @verifies:admin-ui/${id(screen)}`, async ({ page }) => {
        await expectScreenRenders(page, screen)
      })
    })
  }
}
