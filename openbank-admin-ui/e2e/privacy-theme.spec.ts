// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

for (const colorScheme of ['light', 'dark'] as const) {
  test(`${colorScheme} public privacy notice is accessible without a session`, async ({ page }) => {
    const authRequests: string[] = []
    page.on('request', request => {
      if (new URL(request.url()).pathname.startsWith('/api/auth/')) authRequests.push(request.url())
    })
    await page.emulateMedia({ colorScheme })
    await page.goto('/privacy')

    await expect(page.getByRole('heading', { name: 'Know what happens to your operator data.' })).toBeVisible()
    const palette = await page.locator('main').evaluate(element => {
      const style = getComputedStyle(element)
      return {
        background: style.getPropertyValue('--privacy-bg').trim(),
        text: style.getPropertyValue('--privacy-text').trim(),
      }
    })
    expect(palette).toEqual(colorScheme === 'dark'
      ? { background: '#07111f', text: '#e5edf7' }
      : { background: '#f6f8fb', text: '#142842' })

    await page.keyboard.press('Tab')
    await expect(page.getByRole('link', { name: 'Skip to privacy details' })).toBeFocused()
    expect(authRequests).toEqual([])
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}

test('mobile privacy notice has no horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/privacy')
  await expect(page.getByRole('heading', { name: 'The data journey' })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(0)
  await expect(page.getByRole('link', { name: /security@open-bank.tech/ })).toBeVisible()
})
