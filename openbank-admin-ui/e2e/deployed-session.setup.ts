// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Signs in to the DEPLOYED Admin UI through the real Keycloak login form (#13171) and stores the
// resulting Auth.js session for deployed-session.spec.ts. The username comes from a repo variable,
// the password ONLY from the ADMIN_UI_E2E_PASSWORD Actions secret; neither is ever logged, and the
// config disables traces/screenshots so the typed password cannot land in an artifact.
import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import { expect, test as setup } from '@playwright/test'
import { SESSION_STATE } from '../playwright.deployed-session.config'

setup('sign in as the e2e operator through Keycloak @verifies:admin-ui/E2E-SSO', async ({ page, baseURL }) => {
  const username = process.env.ADMIN_UI_E2E_USERNAME
  const password = process.env.ADMIN_UI_E2E_PASSWORD
  // The workflow skips this lane with a visible notice when the secret is absent; reaching here
  // without it is a misconfiguration, so fail loudly rather than skip quietly.
  expect(username, 'ADMIN_UI_E2E_USERNAME is not set').toBeTruthy()
  expect(password, 'ADMIN_UI_E2E_PASSWORD is not set').toBeTruthy()

  await page.goto('/auth/login?callbackUrl=%2Fdashboard')
  await page.getByRole('button', { name: /Keycloak SSO/ }).click()

  // The real Keycloak form, on the identity provider's own origin.
  await page.locator('#username').waitFor({ state: 'visible' })
  expect(new URL(page.url()).origin).not.toBe(new URL(baseURL!).origin)
  await page.locator('#username').fill(username!)
  await page.locator('#password').fill(password!)
  await page.locator('#kc-login').click()

  await page.waitForURL((url) => url.origin === new URL(baseURL!).origin && !url.pathname.startsWith('/auth/'))

  // Prove a session exists by effect, not by the URL alone.
  const session = await (await page.request.get('/api/auth/session')).json()
  expect(session?.user, 'no Auth.js session after the Keycloak login').toBeTruthy()

  mkdirSync(dirname(SESSION_STATE), { recursive: true })
  await page.context().storageState({ path: SESSION_STATE })
})
