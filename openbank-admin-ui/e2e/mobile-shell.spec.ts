// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/services/governance', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ items: [] }),
  }))
  await page.route('**/api/services/health', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ services: [] }),
  }))
})

test('keeps the mobile navigation keyboard-complete and removes it from focus when closed', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/dashboard')

  const menu = page.locator('button[aria-controls="admin-sidebar"]')
  const sidebarControls = page.locator('#admin-sidebar a, #admin-sidebar button:not([disabled])')
  await expect(menu).toBeVisible()
  await expect(page.locator('#admin-sidebar')).toBeHidden()

  await menu.click()
  await expect(menu).toHaveAttribute('aria-expanded', 'true')
  await expect(sidebarControls.first()).toBeFocused()

  await page.keyboard.press('Shift+Tab')
  await expect(sidebarControls.last()).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(sidebarControls.first()).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(menu).toHaveAttribute('aria-expanded', 'false')
  await expect(menu).toBeFocused()
  await expect(page.locator('#admin-sidebar')).toBeHidden()

  await page.keyboard.press('Shift+Tab')
  const skipLink = page.getByRole('link', { name: /Skip to main content|Přeskočit na hlavní obsah/ })
  await expect(skipLink).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#main-content')).toBeFocused()
})
