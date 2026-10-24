// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// DEPLOYED AUTH-GATE check — NOT a screen-behaviour test (#13171).
//
// Run by playwright.deployed.config.ts against the sandbox Admin UI, never against the stubbed
// localhost server. It proves one thing per money-path screen: an UNAUTHENTICATED request is
// redirected to /auth/login with the screen's own callbackUrl and the redirect carries no page
// data. It does not sign in and does not claim an operator journey works — the admin UI
// authenticates through Keycloak authorization-code + Auth.js, so no credential path exists for a
// headless lane (owner decision tracked in the issue linked from #13171).
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test } from '@playwright/test'

// One entry per money-path admin screen (rules.yaml: money_path_services).
const MONEY_PATH_SCREENS = [
  '/payments', '/sepa-instant', '/ledger', '/accounts', '/transactions', '/fx', '/clearing', '/swift',
  '/settlements', '/interest', '/sdd', '/standing-orders', '/treasury', '/cards', '/lending', '/fees',
  '/disputes', '/fraud', '/sanctions', '/pension', '/day-end', '/delegations', '/consents', '/approvals',
]

// The redirect alone proves nothing about the screen: the proxy sends EVERY unauthenticated path to
// /auth/login, so a deleted route would still pass. Each listed path must also still exist as a
// page in the source tree this lane was run from (cwd is openbank-admin-ui for both the workflow
// and `npx playwright test`).
test('auth-gate: every listed screen still has a page.tsx @deployed', () => {
  const missing = MONEY_PATH_SCREENS.filter((screen) => !existsSync(join(process.cwd(), 'src', 'app', screen, 'page.tsx')))
  expect(missing, `listed screens with no src/app/<screen>/page.tsx: ${missing.join(', ')}`).toEqual([])
})

for (const screen of MONEY_PATH_SCREENS) {
  test(`auth-gate: ${screen} redirects unauthenticated visitors to SSO without data @deployed`, async ({ request }) => {
    const response = await request.get(screen, { maxRedirects: 0 })
    expect([302, 303, 307, 308]).toContain(response.status())
    const location = new URL(response.headers()['location'] ?? '', response.url())
    expect(location.pathname).toBe('/auth/login')
    expect(location.searchParams.get('callbackUrl')).toBe(screen)
    // A redirect must not smuggle the page: no table markup, no JSON, only a stub body.
    const body = await response.text()
    expect(body).not.toMatch(/<table|<tbody|"items"\s*:|"accountId"/i)
    expect(body.length).toBeLessThan(2_000)
  })
}
