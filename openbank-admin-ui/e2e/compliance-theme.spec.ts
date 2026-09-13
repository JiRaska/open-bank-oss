// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/docs/compliance — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme keeps the report understandable and WCAG A/AA`, async ({ page }) => {
      await page.goto('/docs/compliance')
      await expect(page.getByRole('heading', { level: 1, name: /Compliance Report|Report compliance/i })).toBeVisible()

      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByText('PSD2 / RTS on SCA').first()).toBeVisible()
      await expect(page.getByText('GDPR', { exact: true }).first()).toBeVisible()
      await expect(page.getByText(/Full regulatory compliance also requires legal documentation|Plná regulatorní compliance vyžaduje také právní dokumentaci/i)).toBeVisible()
      await expect(page.getByText(/Compliant|V souladu/i).first()).toBeVisible()
      await expect(page.getByText(/Warnings|Upozornění/i).first()).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
