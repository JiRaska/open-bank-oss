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
    test(`${theme} theme keeps editorial scope and unknown control evidence understandable`, async ({ page }) => {
      const initialControlsResponsePromise = page.waitForResponse(response =>
        response.url().endsWith('/api/compliance/controls') && response.request().method() === 'GET',
      )
      await page.goto('/docs/compliance')
      const initialControlsResponse = await initialControlsResponsePromise
      expect(initialControlsResponse.ok()).toBeTruthy()
      await expect(page.getByRole('heading', { level: 1, name: /Compliance Report|Report compliance/i })).toBeVisible()

      const themeButton = page.getByRole('button', { name: /Switch to the (light|dark) theme|Přepnout na (světlý|tmavý) motiv/ })
      const currentlyDark = await themeButton.getAttribute('aria-pressed') === 'true'
      if ((theme === 'dark') !== currentlyDark) {
        await themeButton.focus()
        await page.keyboard.press('Enter')
      }
      await expect(themeButton).toHaveAttribute('aria-pressed', String(theme === 'dark'))

      await expect(page.getByText('PSD2 / RTS on SCA').first()).toBeVisible()
      await expect(page.getByText('GDPR', { exact: true }).first()).toBeVisible()
      await expect(page.getByText('47', { exact: true }).first()).toBeVisible()
      await expect(page.getByText(/Without verified mapping|Bez ověřeného mapování/i).first()).toBeVisible()
      await expect(page.getByText(/This page is an editorial inventory of topics. It does not establish legal compliance or operating effectiveness.|Tento přehled je redakční inventář oblastí. Neprokazuje právní shodu ani provozní účinnost kontrol./i)).toBeVisible()
      await expect(page.getByText(/Manifest snapshot collected \d{4}-\d{2}-\d{2}T/)).toBeVisible()
      await expect(page.getByText('Compliant', { exact: true })).toHaveCount(0)
      await expect(page.getByText('Warnings', { exact: true })).toHaveCount(0)

      const refresh = page.getByRole('button', { name: /Refresh catalogue|Obnovit katalog/i })
      await refresh.focus()
      const refreshResponse = page.waitForResponse(response =>
        response.url().endsWith('/api/compliance/controls') && response.request().method() === 'GET',
      )
      await page.keyboard.press('Enter')
      expect((await refreshResponse).ok()).toBeTruthy()
      await expect(refresh).toBeEnabled()
      await expect(themeButton).toHaveAttribute('aria-pressed', String(theme === 'dark'))

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
