// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

const surfaces = [
  {
    path: '/auth/error?error=AccessDenied&callbackUrl=https%3A%2F%2Fattacker.example',
    heading: 'We could not sign you in',
    evidence: 'The identity provider did not grant access',
  },
  {
    path: '/auth/forbidden?path=%2F%2Fattacker.example%2Fprivate',
    heading: 'This area is not in your role',
    evidence: 'This is a normal security control',
  },
] as const

for (const colorScheme of ['light', 'dark'] as const) {
  for (const surface of surfaces) {
    test(`${colorScheme} ${surface.heading} recovery is accessible and session-free`, async ({ page }) => {
      const authRequests: string[] = []
      page.on('request', request => {
        if (new URL(request.url()).pathname.startsWith('/api/auth/')) authRequests.push(request.url())
      })
      await page.emulateMedia({ colorScheme })
      await page.goto(surface.path)

      await expect(page.getByRole('heading', { name: surface.heading })).toBeVisible()
      await expect(page.getByText(surface.evidence, { exact: false })).toBeVisible()
      const palette = await page.locator('main').evaluate(element => {
        const style = getComputedStyle(element)
        return { background: style.getPropertyValue('--privacy-bg').trim(), text: style.getPropertyValue('--privacy-text').trim() }
      })
      expect(palette).toEqual(colorScheme === 'dark'
        ? { background: '#07111f', text: '#e5edf7' }
        : { background: '#f6f8fb', text: '#142842' })
      expect(authRequests).toEqual([])

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }
}

test('mobile forbidden recovery does not echo an external destination or overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/auth/forbidden?path=%2F%2Fattacker.example%2Fprivate')

  await expect(page.getByText('attacker.example', { exact: false })).toHaveCount(0)
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(0)
  await expect(page.getByRole('button', { name: 'Return to your dashboard' })).toBeVisible()
})
