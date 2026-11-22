// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from './helpers/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/docs/compliance — derived trace, semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme renders the derived compliance trace accessibly`, async ({ page }) => {
      const initialTraceResponsePromise = page.waitForResponse(response =>
        response.url().endsWith('/api/compliance/trace') && response.request().method() === 'GET',
      )
      await page.goto('/docs/compliance')
      const initialTraceResponse = await initialTraceResponsePromise
      expect(initialTraceResponse.ok()).toBeTruthy()
      const trace = await initialTraceResponse.json() as { status: string; summary?: { controls: number } }
      expect(trace.status).toBe('available')
      expect(trace.summary?.controls ?? 0).toBeGreaterThan(0)
      await expect(page.getByRole('heading', { level: 1, name: /Compliance posture|Stav compliance/i })).toBeVisible()
      const main = page.getByRole('main')
      await expect(main).toHaveCount(1)

      const themeButton = page.getByRole('button', { name: /Switch to the (light|dark) theme|Přepnout na (světlý|tmavý) motiv/ })
      const currentlyDark = await themeButton.getAttribute('aria-pressed') === 'true'
      if ((theme === 'dark') !== currentlyDark) {
        await themeButton.focus()
        await page.keyboard.press('Enter')
      }
      await expect(themeButton).toHaveAttribute('aria-pressed', String(theme === 'dark'))

      await expect(page.getByText(/Controls with machine evidence|Kontroly se strojovým důkazem/i).first()).toBeVisible()
      await expect(main.getByText(/The verdict is computed by code|Verdikt počítá kód/i)).toHaveCount(1)
      await expect(page.getByText('Compliant', { exact: true })).toHaveCount(0)

      for (const tab of [/Trace \(audit\)|Trasa \(audit\)/, /By framework|Podle oblasti/, /Gaps|Mezery/]) {
        await page.getByRole('tab', { name: tab }).click()
        await expect(page.getByRole('tabpanel')).toBeVisible()
      }

      const refresh = page.getByRole('button', { name: /^Refresh$|^Obnovit$/i })
      const refreshResponse = page.waitForResponse(response =>
        response.url().endsWith('/api/compliance/trace') && response.request().method() === 'GET',
      )
      await refresh.click()
      expect((await refreshResponse).ok()).toBeTruthy()
      await expect(refresh).toBeEnabled()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
