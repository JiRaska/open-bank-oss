// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test('keeps shell and dialog controls safely tappable on coarse pointers', async ({ browser, baseURL }) => {
  if (!baseURL) throw new Error('Playwright baseURL is required')
  const context = await browser.newContext({
    baseURL,
    hasTouch: true,
    isMobile: true,
    viewport: { width: 390, height: 844 },
  })

  try {
    await signInAsOperator(context, baseURL)
    const page = await context.newPage()
    await page.route('**/api/**', route => {
      if (new URL(route.request().url()).pathname.startsWith('/api/auth/')) return route.continue()
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"temporarily unavailable"}' })
    })
    await page.goto('/dashboard')

    const menu = page.getByRole('button', { name: /Otevřít navigaci|Open navigation/ })
    await expect(menu).toBeVisible()
    const menuBox = await menu.boundingBox()
    expect(menuBox).not.toBeNull()
    expect(menuBox!.width).toBeGreaterThanOrEqual(44)
    expect(menuBox!.height).toBeGreaterThanOrEqual(44)

    await page.getByRole('button', { name: /Rychlé hledání \(⌘K\)|Quick search \(⌘K\)/ }).click()
    const close = page.getByRole('button', { name: /Zavřít|Close/ })
    await expect(close).toBeVisible()
    const closeBox = await close.boundingBox()
    expect(closeBox).not.toBeNull()
    expect(closeBox!.width).toBeGreaterThanOrEqual(44)
    expect(closeBox!.height).toBeGreaterThanOrEqual(44)
  } finally {
    await context.close()
  }
})
