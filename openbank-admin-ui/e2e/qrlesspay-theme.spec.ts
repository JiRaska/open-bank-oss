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
    test(`${theme} theme keeps the payment safety model understandable and WCAG A/AA`, async ({ page, baseURL }) => {
      // The server and ThemeProvider read this same preference, so the first paint and hydrated
      // tree agree. Mutating <html> directly races the provider's post-hydration applyTheme.
      await page.context().addCookies([{ name: 'ob-admin-theme', value: theme, url: baseURL! }])
      // The educational dossier is usable once its document is interactive. Waiting for the
      // global `load` event also waits for unrelated late resources and has timed out twice
      // under the parallel CI dev server even though the rendered page was ready. The heading
      // and safety-model assertions below remain the authoritative readiness proof.
      await page.goto('/docs/qrlesspay', { waitUntil: 'domcontentloaded' })
      await expect(page.getByRole('heading', { level: 1, name: /QRlessPay/ })).toBeVisible()

      if (theme === 'dark') {
        await expect(page.locator('html')).toHaveClass(/\bdark\b/)
        await expect(page.locator('.qrlesspay-doc .card').first()).toHaveCSS('background-color', 'rgb(17, 24, 39)')
      } else {
        await expect(page.locator('html')).not.toHaveClass(/\bdark\b/)
      }

      const securityLayers = page.getByRole('heading', { level: 2, name: /Security layers \(defense in depth\)|Bezpečnostní vrstvy/i })
      await expect(page.locator('.qrlesspay-doc')).toHaveCount(1)
      await expect(securityLayers).toHaveCount(1)
      await expect(securityLayers).toBeVisible()
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
