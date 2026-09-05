// SPDX-License-Identifier: Apache-2.0
import { expect, test, type Page } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

async function holdClientSession(page: Page) {
  let releaseSession!: () => void
  const heldSession = new Promise<void>(resolve => { releaseSession = () => resolve() })
  let signalSessionRequested!: () => void
  const sessionRequested = new Promise<void>(resolve => { signalSessionRequested = () => resolve() })
  await page.route('**/api/auth/session', async route => {
    signalSessionRequested()
    await heldSession
    await route.continue()
  })
  return { releaseSession, sessionRequested }
}

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
  const { releaseSession, sessionRequested } = await holdClientSession(page)

  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/dashboard')
  // The server-rendered toggle is visible before the client session has resolved. Opening the
  // drawer in that window must focus the first control from the final permission-filtered nav,
  // not a fallback control that happens to be first while roles are still empty.
  await sessionRequested

  const menu = page.locator('button[aria-controls="admin-sidebar"]')
  const sidebar = page.locator('#admin-sidebar')
  const sidebarControls = page.locator('#admin-sidebar a, #admin-sidebar button:not([disabled])')
  await expect(menu).toBeVisible()
  await expect(sidebar).toBeHidden()

  await menu.click()
  await expect(menu).toHaveAttribute('aria-expanded', 'true')
  await expect(sidebar).toBeFocused()
  releaseSession()
  await expect(sidebarControls.first()).toHaveAttribute('href', '/system/tests')
  await expect(sidebarControls.first()).toBeFocused()

  await page.keyboard.press('Shift+Tab')
  await expect(sidebarControls.last()).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(sidebarControls.first()).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(menu).toHaveAttribute('aria-expanded', 'false')
  await expect(menu).toBeFocused()
  await expect(sidebar).toBeHidden()

  await page.keyboard.press('Shift+Tab')
  const skipLink = page.getByRole('link', { name: /Skip to main content|Přeskočit na hlavní obsah/ })
  await expect(skipLink).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#main-content')).toBeFocused()
})

test('does not steal focus when an operator moves inside the drawer while permissions resolve', async ({ page }) => {
  const { releaseSession, sessionRequested } = await holdClientSession(page)

  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/dashboard')
  await sessionRequested

  const menu = page.locator('button[aria-controls="admin-sidebar"]')
  const sidebar = page.locator('#admin-sidebar')
  const dashboard = page.locator('#admin-sidebar a[href="/dashboard"]')
  const sidebarControls = page.locator('#admin-sidebar a, #admin-sidebar button:not([disabled])')

  await menu.click()
  await expect(sidebar).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(dashboard).toBeFocused()

  releaseSession()
  await expect(sidebarControls.first()).toHaveAttribute('href', '/system/tests')
  await page.evaluate(() => new Promise<void>(resolve => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
  }))
  await expect(dashboard).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(menu).toHaveAttribute('aria-expanded', 'false')
  await expect(menu).toBeFocused()
  await expect(sidebar).toBeHidden()
})
