// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/docs/cluster — semantic theme', () => {
  test('mobile education stacks diagrams and keeps the comparison table keyboard-scrollable', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/docs/cluster', { waitUntil: 'domcontentloaded' })
    await expect(page.getByRole('button', { name: /admin-ui/i })).toBeVisible()
    await expect(page.getByRole('note')).toContainText('Repository snapshot')
    await expect(page.getByRole('note')).toContainText('not live cluster health')
    await expect(page.getByRole('note').locator('time')).toBeVisible()

    const layout = await page.evaluate(() => {
      const defense = document.querySelector('[data-testid="cluster-defense-layout"]')
      const anatomy = document.querySelector('[data-testid="cluster-anatomy-layout"]')
      const stacked = (element: Element | null) => {
        const first = element?.firstElementChild?.getBoundingClientRect()
        const second = element?.lastElementChild?.getBoundingClientRect()
        return !!first && !!second && second.top >= first.bottom
      }
      return { viewport: innerWidth, document: document.documentElement.scrollWidth, defense: stacked(defense), anatomy: stacked(anatomy) }
    })
    expect(layout.document).toBeLessThanOrEqual(layout.viewport)
    expect(layout.defense).toBe(true)
    expect(layout.anatomy).toBe(true)

    const comparison = page.getByRole('region', { name: 'Plan versus reality table' })
    await expect(comparison).toBeVisible()
    await expect(comparison).toHaveAttribute('tabindex', '0')
    expect(await comparison.evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true)
    await comparison.focus()
    await page.keyboard.press('ArrowRight')
    await expect.poll(() => comparison.evaluate(element => element.scrollLeft)).toBeGreaterThan(0)
  })

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
