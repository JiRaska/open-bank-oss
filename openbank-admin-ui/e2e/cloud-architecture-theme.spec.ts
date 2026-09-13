// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/docs/cloud-architecture — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme preserves plan-vs-reality and WCAG A/AA`, async ({ page }) => {
      await page.route('**/api/infra/status', route => route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ kafka: { status: 'UP' }, postgres: { status: 'DOWN' } }),
      }))
      await page.goto('/docs/cloud-architecture')
      await expect(page.getByRole('heading', { level: 1, name: /Cloud Architecture \(AWS\)|Cloud architektura \(AWS\)/i })).toBeVisible()

      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByText(/Diagram = strategy|Diagram = strategie/i)).toBeVisible()
      const kafka = page.getByRole('button', { name: /Strimzi \/ Kafka.*Live/i })
      await kafka.click()
      await expect(kafka).toHaveAttribute('aria-pressed', 'true')
      await expect(page.getByRole('region', { name: /Architecture element details|Detail architektonického prvku/i })).toContainText(/config drift/i)

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
