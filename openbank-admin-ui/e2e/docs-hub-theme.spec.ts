// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/docs — educational hub theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme keeps the learning paths understandable and accessible`, async ({ page }) => {
      await page.goto('/docs')
      await expect(page.getByRole('heading', { level: 1, name: /OpenBank Documentation Portal|Dokumentační portál OpenBank/i })).toBeVisible()

      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByRole('link', { name: /Identity & Deduplication|Identita a deduplikace/i })).toBeVisible()
      await expect(page.locator('a[href="/docs/api"]').filter({ hasText: /Live catalog/i })).toContainText(/API Catalog|API Katalog/i)
      await expect(page.getByRole('link', { name: /Zero-Trust Security Map/i })).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
