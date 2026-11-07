// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Signs in to the DEPLOYED Admin UI through the real Keycloak login form (#13171) and stores the
// resulting Auth.js sessions for deployed-session.spec.ts. Passwords come ONLY from Actions secrets;
// none is ever logged, and the config disables traces/screenshots so a typed password cannot land
// in an artifact.
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { expect, test as setup, type Page } from '@playwright/test'
import { OPERATOR_STATE, SESSION_STATE } from '../playwright.deployed-session.config'
import { watchTraffic } from './deployed-session-guard'

async function signIn(page: Page, origin: string, username: string | undefined, password: string | undefined, state: string) {
  // The workflow skips this lane with a visible notice when a secret is absent; reaching here
  // without one is a misconfiguration, so fail loudly rather than skip quietly.
  expect(username, 'operator username is not set').toBeTruthy()
  expect(password, 'operator password is not set').toBeTruthy()

  await page.goto('/auth/login?callbackUrl=%2Fdashboard')
  await page.getByRole('button', { name: /Keycloak SSO/ }).click()

  // The real Keycloak form, on the identity provider's own origin.
  await page.locator('#username').waitFor({ state: 'visible' })
  expect(new URL(page.url()).origin).not.toBe(origin)
  // Armed here, not earlier: starting the SSO redirect is the login page's own POST to /api/auth/.
  // From this point the app is only reached by the callback and the post-login landing page.
  const { writes } = watchTraffic(page)
  await page.locator('#username').fill(username!)
  await page.locator('#password').fill(password!)
  await page.locator('#kc-login').click()

  await page.waitForURL((url) => url.origin === origin && !url.pathname.startsWith('/auth/'))
  await page.waitForLoadState('load')
  await page.waitForTimeout(1_000) // a landing page may write from an effect, after load
  expect(writes, 'the post-login landing page attempted a write').toEqual([])

  // Prove a session exists by effect, not by the URL alone.
  const session = await (await page.request.get('/api/auth/session')).json()
  expect(session?.user, 'no Auth.js session after the Keycloak login').toBeTruthy()

  mkdirSync(dirname(state), { recursive: true })
  await page.context().storageState({ path: state })
}

// The Test Intelligence journey verdict needs one paint sample; without it the run reads not-run.
async function recordVitals(page: Page) {
  const output = process.env.OPENBANK_BROWSER_VITALS_OUTPUT
  if (!output) return
  await page.waitForTimeout(1_000)
  const vitals = await page.evaluate(() => ({
    fcp: performance.getEntriesByName('first-contentful-paint')[0]?.startTime,
    cls: (window as unknown as { __openbankCls?: number }).__openbankCls,
  }))
  if (!Number.isFinite(vitals.fcp) || !Number.isFinite(vitals.cls)) return
  mkdirSync(dirname(output), { recursive: true })
  writeFileSync(output, JSON.stringify({
    schemaVersion: 1,
    journey: 'admin-money-path-screens',
    browser: 'chromium',
    metrics: { fcpMs: Math.round(vitals.fcp!), cls: Math.round(vitals.cls! * 1000) / 1000 },
  }))
}

setup('sign in as the e2e operator through Keycloak @verifies:admin-ui/E2E-SSO', async ({ page, baseURL }) => {
  await page.addInitScript(() => {
    const w = window as unknown as { __openbankCls: number }
    w.__openbankCls = 0
    new PerformanceObserver((entries) => {
      for (const entry of entries.getEntries() as unknown as { hadRecentInput: boolean; value: number }[]) {
        if (!entry.hadRecentInput) w.__openbankCls += entry.value
      }
    }).observe({ type: 'layout-shift', buffered: true })
  })
  await signIn(page, new URL(baseURL!).origin, process.env.ADMIN_UI_E2E_USERNAME, process.env.ADMIN_UI_E2E_PASSWORD, SESSION_STATE)
  await recordVitals(page)
})

setup('sign in as the synthetic operator through Keycloak @verifies:admin-ui/E2E-SSO', async ({ page, baseURL }) => {
  await signIn(page, new URL(baseURL!).origin, process.env.SYNTHETIC_OPERATOR_USERNAME, process.env.SYNTHETIC_OPERATOR_PASSWORD, OPERATOR_STATE)
})
