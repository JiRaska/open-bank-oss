// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme } from './helpers/theme'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('AI collaboration education', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme explains governed human-first collaboration`, async ({ page }) => {
      await setOperatorTheme(page, theme)
      await page.goto('/iaops')

      const crew = page.getByRole('region', { name: /Meet the colleagues|Seznamte se s kolegy/i })
      await expect(crew).toBeVisible()
      await expect(crew.getByText(/Human decides|Člověk rozhodne/i)).toBeVisible()
      const crewResults = await new AxeBuilder({ page })
        .include('#iaops-crew')
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(crewResults.violations).toEqual([])

      const mesh = page.locator('#ai-swarm')
      await expect(mesh.getByRole('heading', { name: /What is the AI swarm|Co je AI swarm/i })).toBeVisible()
      await expect(mesh.getByText(/A human has the final say|Člověk má poslední slovo/i)).toBeVisible()
      await expect(mesh.getByText(/Agents never write directly|Agenti do business služby přímo nezapisují/i)).toBeVisible()
      await expect(mesh.getByText(/runtime allowlist/i)).toBeVisible()

      const connector = await mesh.locator('li').first().evaluate(element =>
        getComputedStyle(element, '::after').content,
      )
      expect(connector).toContain('→')

      const results = await new AxeBuilder({ page })
        .include('#ai-swarm')
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }

  test('mobile layout preserves the four-step flow vertically', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 844 })
    await page.goto('/iaops')

    const mesh = page.locator('#ai-swarm')
    await expect(mesh.locator('li')).toHaveCount(4)
    const connector = await mesh.locator('li').first().evaluate(element =>
      getComputedStyle(element, '::after').content,
    )
    expect(connector).toContain('↓')
    await expect(page.getByRole('region', { name: /Posuvné regulační mapování AI operací|Scrollable AI operations regulatory mapping/ })).toBeVisible()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  })
})
