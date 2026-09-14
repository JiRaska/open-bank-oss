// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

for (const colorScheme of ['light', 'dark'] as const) {
  test(`${colorScheme} secure login is accessible and session-free`, async ({ page }) => {
    const authRequests: string[] = []
    page.on('request', request => {
      if (new URL(request.url()).pathname.startsWith('/api/auth/')) authRequests.push(request.url())
    })
    await page.emulateMedia({ colorScheme })
    await page.goto('/auth/login?error=SessionExpired&callbackUrl=https%3A%2F%2Fattacker.example')

    await expect(page.getByRole('heading', { name: 'Explore operations with confidence.' })).toBeVisible()
    await expect(page.getByRole('alert').filter({ hasText: 'Your session expired' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Continue with Keycloak SSO' })).toBeVisible()
    await expect(page.getByText('Role-based access, four-eyes approvals and audit evidence remain active throughout your session.')).toBeVisible()
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

test('mobile reduced-motion login stays usable without overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/auth/login')

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(0)
  await expect(page.getByRole('link', { name: 'Privacy and data protection' })).toBeVisible()
  const sceneTransitionSeconds = await page.locator('img').first().evaluate(element =>
    Number.parseFloat(getComputedStyle(element).transitionDuration),
  )
  expect(sceneTransitionSeconds).toBeLessThanOrEqual(0.00001)
})
