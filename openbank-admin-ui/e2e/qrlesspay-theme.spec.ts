// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/docs/qrlesspay — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme keeps the payment safety model understandable and WCAG A/AA`, async ({ page }) => {
      await page.goto('/docs/qrlesspay')
      await expect(page.getByRole('heading', { level: 1, name: /QRlessPay/ })).toBeVisible()

      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByText(/Security layers \(defense in depth\)|Bezpečnostní vrstvy/i)).toBeVisible()
      await expect(page.getByText(/No money moves without payer confirmation|Žádné peníze se nehnou bez potvrzení plátce/i)).toBeVisible()
      await expect(page.getByRole('img', { name: /QRlessPay handshake sequence|Sekvence QRlessPay handshaku/i })).toBeVisible()
      await expect(page.getByRole('link', { name: /ADR-0095/ }).first()).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
