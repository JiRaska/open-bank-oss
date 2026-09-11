// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/docs/cluster — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme preserves cluster education and WCAG A/AA`, async ({ page }) => {
      await page.goto('/docs/cluster')
      await expect(page.getByRole('heading', { level: 1, name: /Cluster & container.*topology and hardening|Cluster & kontejner.*topologie a hardening/i })).toBeVisible()

      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByText(/domain isolation|doménová izolace/i).first()).toBeVisible()
      const adminNamespace = page.getByRole('button', { name: /admin-ui/i })
      await adminNamespace.click()
      await expect(adminNamespace).toHaveAttribute('aria-expanded', 'true')
      await expect(page.getByRole('region', { name: 'admin-ui' })).toContainText(/Admin portal|Admin portál/i)
      await expect(page.getByText(/OPENBANK ·/i)).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
