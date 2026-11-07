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
import { watchTraffic } from './deployed-session-guard'
import { pathAllowedByRoles } from '../src/lib/auth/roles'

const E2E_ROLES = ['ROLE_VIEWER', 'ROLE_AUDITOR']
const OPERATOR_ROLES = ['ROLE_OPERATOR']

const MONEY_PATH_SCREENS = [
  '/payments', '/sepa-instant', '/ledger', '/accounts', '/transactions', '/fx', '/clearing', '/swift',
  '/settlements', '/interest', '/sdd', '/standing-orders', '/treasury', '/cards', '/lending', '/fees',
  '/disputes', '/fraud', '/sanctions', '/pension', '/day-end', '/delegations', '/consents', '/approvals',
]

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
  // Bounded: a polling screen never goes idle, and that is not a failure.
  await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {})
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
