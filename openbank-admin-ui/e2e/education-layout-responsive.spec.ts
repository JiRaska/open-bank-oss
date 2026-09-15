// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.setViewportSize({ width: 320, height: 760 })
})

test('bundled service documentation keeps navigation and content readable', async ({ page }) => {
  await page.goto('/services/libs/docs')
  const layout = page.getByTestId('service-docs-layout')
  await expect(layout).toBeVisible()
  expect(await layout.evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('cluster defense-in-depth education stacks its visual and explanations', async ({ page }) => {
  await page.goto('/docs/cluster')
  const layout = page.getByTestId('cluster-defense-layout')
  await expect(layout).toBeVisible()
  expect(await layout.evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
