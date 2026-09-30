// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

const formRoutes = [
  '/treasury/nostro',
  '/treasury/deals/new',
  '/treasury/positions',
  '/balance-sheet/snapshots',
  '/balance-sheet/curve-sets',
  '/balance-sheet/ledger-backfill',
] as const

test.describe('domain workbench form layout', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInWithRoles(context, baseURL!, ['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_RISK', 'ROLE_FINANCE', 'ROLE_TREASURY_DEALER', 'ROLE_TREASURY_APPROVER'])
  })

  for (const width of [390, 800, 1280]) {
    for (const route of formRoutes) {
      test(`${route} keeps controls inset and the page inside ${width}px`, async ({ page }) => {
        await page.setViewportSize({ width, height: 900 })
        await page.goto(route)

        const field = page.locator('.ob-domain-workbench .card').filter({ has: page.locator('input.input, select.input, textarea.input') }).first()
        await expect(field).toBeVisible({ timeout: 15_000 })
        const panelBox = await field.boundingBox()
        const controlBox = await field.locator('input.input, select.input, textarea.input').first().boundingBox()
        expect(panelBox).not.toBeNull()
        expect(controlBox).not.toBeNull()
        expect(controlBox!.x - panelBox!.x).toBeGreaterThanOrEqual(15)
        expect(controlBox!.x + controlBox!.width).toBeLessThanOrEqual(panelBox!.x + panelBox!.width - 15)

        const horizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
        expect(horizontalOverflow).toBeLessThanOrEqual(1)
        if (route === '/treasury/nostro') {
          const loadButton = page.getByRole('button', { name: /^Load$|^Načíst$/ })
          const inlinePadding = await loadButton.evaluate(button => parseFloat(getComputedStyle(button).paddingInlineStart))
          expect(inlinePadding).toBeGreaterThanOrEqual(12)
        }
      })
    }
  }
})
