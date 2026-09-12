// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/docs/qrlesspay-readiness — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme preserves candid verdicts and WCAG A/AA`, async ({ page }) => {
      await page.goto('/docs/qrlesspay-readiness')
      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByRole('heading', { level: 1, name: /QRlessPay.*readiness assessment|QRlessPay.*posouzení připravenosti/i })).toBeVisible()
      await expect(page.getByText(/self-assessment.*not an approval|sebehodnocení.*ne schválení/i)).toBeVisible()
      await expect(page.getByText(/may change the design|může změnit návrh/i)).toBeVisible()
      await expect(page.getByText(/not started|nezačato/i)).toBeVisible()
      await expect(page.getByRole('heading', { name: /Recommended sequence|Doporučené pořadí/i })).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
