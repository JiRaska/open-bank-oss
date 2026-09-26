// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/docs/document-management — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme preserves the educational model and WCAG A/AA`, async ({ page }) => {
      await page.goto('/docs/document-management')
      await expect(page.getByRole('heading', { level: 1, name: /Document Management|Správa dokumentů/i })).toBeVisible()

      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)

      // Global theme variables transition deliberately; audit the settled visual state.
      await page.waitForTimeout(300)

      await expect(page.getByText(/Client — one-time certificate|Klient — jednorázový certifikát/i)).toBeVisible()
      await expect(page.getByText(/The bank — stable organizational seal|Banka — stabilní organizační pečeť/i)).toBeVisible()
      await expect(page.getByText(/non-money-path|mimo peněžní cestu/i).first()).toBeVisible()
      await expect(page.getByRole('link', { name: /ADR-0161/ }).first()).toBeVisible()
      await expect(page.getByRole('link', { name: /ADR-0162/ }).first()).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
