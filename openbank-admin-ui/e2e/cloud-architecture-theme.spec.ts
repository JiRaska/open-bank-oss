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
        body: JSON.stringify({ kafka: { status: 'UP', checkedAt: new Date().toISOString() }, postgres: { status: 'DOWN', checkedAt: new Date().toISOString() } }),
      }))
      await page.route('**/api/platform-versions', route => route.fulfill({ status: 503, body: '{}' }))
      await page.goto('/docs/cloud-architecture')
      await expect(page.getByRole('heading', { level: 1, name: /Cloud Architecture \(AWS\)|Cloud architektura \(AWS\)/i })).toBeVisible()

      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByText(/Diagram = strategy|Diagram = strategie/i)).toBeVisible()
      const kafka = page.getByRole('button', { name: /Strimzi \/ Kafka UP.*Probe reachable/i })
      await kafka.click()
      await expect(kafka).toHaveAttribute('aria-pressed', 'true')
      await expect(page.getByRole('region', { name: /Architecture element details|Detail architektonického prvku/i })).toContainText(/probe measures broker listener reachability/i)

      await expect(page.getByRole('button', { name: /CloudNativePG.*Declared/i })).toBeVisible()
      await page.getByRole('button', { name: /Close architecture details/i }).click()
      await expect(kafka).toHaveAttribute('aria-pressed', 'false')

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
