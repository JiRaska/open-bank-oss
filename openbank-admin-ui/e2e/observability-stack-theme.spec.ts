// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/observability/stack — semantic theme', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme keeps the telemetry journey understandable and WCAG A/AA`, async ({ page }) => {
      await page.goto('/observability/stack')
      await expect(page.getByRole('heading', { level: 1, name: /How our observability stack works|Jak funguje náš observability stack/i })).toBeVisible()

      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(300)

      await expect(page.getByText('Prometheus', { exact: true }).first()).toBeVisible()
      await expect(page.getByText('Tempo', { exact: true }).first()).toBeVisible()
      await expect(page.getByText(/Synthetics|Syntetika/i).first()).toBeVisible()
      await expect(page.getByRole('img', { name: /Telemetry flow and on-call chain diagram|Diagram toku telemetrie a on-call řetězce/i })).toBeVisible()
      await expect(page.getByRole('link', { name: /Back to metrics|Zpět na metriky/i })).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
})
