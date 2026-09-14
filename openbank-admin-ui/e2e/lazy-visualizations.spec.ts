// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'en'))
})

test('loads the deferred Mermaid engine and renders the authentication flow', async ({ page }) => {
  await page.goto('/docs/auth-flow')
  await expect(page.getByRole('heading', { name: 'Authentication Flow' })).toBeVisible()

  await page.getByRole('button', { name: '② Tokeny' }).click()

  const diagram = page.locator('svg[id^="mermaid-"]')
  await expect(diagram).toBeVisible()
  await expect(diagram).toContainText('Browser')
  await expect(page.getByText('Anatomie access tokenu (JWT)')).toBeVisible()
})

test('loads the deferred feedback chart without delaying the evidence table', async ({ page }) => {
  await page.route('**/api/feedback/screen-feedback', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      available: true,
      from: '2026-08-15',
      to: '2026-09-14',
      screens: [{
        screenId: '/payments', bugs: 3, ideas: 2, confusing: 1, total: 6,
        withScreenshot: 0, lastSeen: '2026-09-14T08:00:00Z',
      }],
      recent: [{
        reference: 'feedback-1', occurredAt: '2026-09-14T08:00:00Z', screenId: '/payments',
        category: 'CONFUSING', comment: 'Explain the settlement state', platform: 'WEB',
        appVersion: '0.244.0', osVersion: 'macOS 15', locale: 'en', theme: 'dark',
        screenshotKey: '', screenshotStatus: 'NONE',
      }],
      context: [{
        screenId: '/payments', platform: 'WEB', osVersion: 'macOS 15', theme: 'dark',
        locale: 'en', bugs: 3, submissions: 6,
      }],
    }),
  }))

  await page.goto('/feedback')

  await expect(page.getByText('Explain the settlement state')).toBeVisible()
  await expect(page.locator('.recharts-wrapper')).toBeVisible()
  await expect(page.getByText('Where it breaks')).toBeVisible()
})
