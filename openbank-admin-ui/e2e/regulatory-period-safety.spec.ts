// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const draft = {
  id: 'e9e37077-3b14-4e52-a450-d2a6616f616d',
  period: '2026-07',
  periodType: 'MONTH',
  from: '2026-07-01',
  to: '2026-07-31',
  status: 'DRAFT',
  evidenceState: 'LINES_V1',
  computedAt: '2026-08-01T00:00:00Z',
  accountCount: 14,
  contentHash: 'a'.repeat(64),
  draftedBy: 'maker-sub',
  frozenBy: null,
  frozenAt: null,
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('checker verifies, explicitly confirms and freezes one immutable regulatory period', async ({ page }) => {
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'en'))
  let freezeRequests = 0

  await page.route('**/api/svc/ledger-service/api/v1/ledger/periods/MONTH/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/verify')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ period: draft.period, status: 'DRAFT', matches: true, balanced: true, recomputedAt: '2026-08-02T00:00:00Z' }),
      })
    }
    if (path.endsWith('/freeze') && request.method() === 'POST') {
      freezeRequests += 1
      await new Promise(resolve => setTimeout(resolve, 150))
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ...draft, status: 'FROZEN', frozenBy: 'e2e-operator', frozenAt: '2026-08-02T00:00:00Z' }),
      })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(draft) })
  })

  await page.goto('/day-end?tab=regulatory')
  const freeze = page.getByRole('button', { name: 'Freeze period (checker)' })
  await expect(freeze).toBeDisabled()

  await page.getByRole('button', { name: 'Verify independently' }).click()
  await expect(page.getByRole('status')).toContainText('The evidence hash matches and the trial balance is balanced.')
  await expect(freeze).toBeDisabled()

  await page.getByRole('checkbox', { name: /I confirm an independent review/ }).check()
  await expect(freeze).toBeEnabled()
  await freeze.click()

  await expect(page.getByRole('status')).toContainText('The period is frozen as immutable regulatory evidence.')
  await expect(page.getByRole('link', { name: 'Open FINREP/COREP preview' })).toHaveAttribute('href', '/regulatory')
  expect(freezeRequests).toBe(1)
})
