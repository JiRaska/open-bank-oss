// SPDX-License-Identifier: Apache-2.0
import { expect, test, type Page } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

async function holdClientSession(page: Page, replacement?: object) {
  let releaseSession!: () => void
  const heldSession = new Promise<void>(resolve => { releaseSession = () => resolve() })
  let signalSessionRequested!: () => void
  const sessionRequested = new Promise<void>(resolve => { signalSessionRequested = () => resolve() })
  await page.route('**/api/auth/session', async route => {
    signalSessionRequested()
    await heldSession
    if (replacement) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(replacement) })
    } else {
      await route.continue()
    }
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
  // `loading.tsx` is a sibling streaming fallback in production. Wait until Next has replaced it
  // with the hydrated shell before asserting landmark/control cardinality.
  await expect(page.locator('body > .ob-app-content')).toHaveCount(0)
  await expect(page.locator('#admin-sidebar')).toHaveCount(1)

  const menu = page.locator('button[aria-controls="admin-sidebar"]:visible')
  const sidebar = page.locator('#admin-sidebar:visible')
  const sidebarControls = sidebar.locator('a, button:not([disabled])')
  await expect(menu).toBeVisible()
  await expect(sidebar).toBeHidden()

  await menu.click()
  await expect(menu).toHaveAttribute('aria-expanded', 'true')
  await expect(sidebar).toBeFocused()
  await expect(sidebar).toHaveCSS('transform', 'matrix(1, 0, 0, 1, 0, 0)')
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
  await expect(page.locator('#admin-sidebar')).toHaveJSProperty('inert', true)

  await menu.press('Shift+Tab')
  const skipLink = page.locator('a.ob-skip-link:visible')
  await expect(skipLink).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#main-content:visible')).toBeFocused()
})

test('keeps the desktop sidebar focusable across viewport changes', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/dashboard')
  await expect(page.locator('body > .ob-app-content')).toHaveCount(0)
  const sidebar = page.locator('#admin-sidebar')
  await expect(sidebar).toHaveCount(1)
  await expect(sidebar).toHaveJSProperty('inert', true)

  await page.setViewportSize({ width: 1280, height: 844 })
  await expect(sidebar).toHaveJSProperty('inert', false)
  const dashboard = sidebar.locator('a[href="/dashboard"]').first()
  await dashboard.focus()
  await expect(dashboard).toBeFocused()

  await page.setViewportSize({ width: 390, height: 844 })
  await expect(sidebar).toHaveJSProperty('inert', true)
})

test('does not steal focus when an operator moves inside the drawer while permissions resolve', async ({ page }) => {
  const { releaseSession, sessionRequested } = await holdClientSession(page)

  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/dashboard')
  await sessionRequested
  await expect(page.locator('body > .ob-app-content')).toHaveCount(0)
  await expect(page.locator('#admin-sidebar')).toHaveCount(1)

  const menu = page.locator('button[aria-controls="admin-sidebar"]:visible')
  const sidebar = page.locator('#admin-sidebar:visible')
  const sidebarControls = sidebar.locator('a, button:not([disabled])')

  await menu.click()
  await expect(sidebar).toBeFocused()
  await expect(sidebar).toHaveCSS('transform', 'matrix(1, 0, 0, 1, 0, 0)')
  await page.waitForTimeout(250)
  const initiallyChosenControl = sidebarControls.first()
  await initiallyChosenControl.focus()
  await expect(initiallyChosenControl).toBeFocused()
  const chosenHref = await initiallyChosenControl.getAttribute('href')
  expect(chosenHref).not.toBeNull()
  const chosenControl = page.locator(`#admin-sidebar:visible a[href="${chosenHref}"]`)
  await expect(chosenControl).toBeFocused()

  releaseSession()
  await expect(sidebarControls.first()).toHaveAttribute('href', '/system/tests')
  await page.evaluate(() => new Promise<void>(resolve => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
  }))
  await expect(chosenControl).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(menu).toHaveAttribute('aria-expanded', 'false')
  await expect(menu).toBeFocused()
  await expect(sidebar).toBeHidden()
})

test('keeps Escape available when a permission refresh removes the focused link', async ({ page }) => {
  const { releaseSession, sessionRequested } = await holdClientSession(page, {
    user: {
      name: 'E2E Operator',
      email: 'e2e-operator@openbank.test',
      roles: ['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_AUDITOR', 'ROLE_COMPLIANCE', 'ROLE_PAYMENTS'],
    },
    expires: '2099-01-01T00:00:00.000Z',
  })

  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/dashboard')
  await sessionRequested
  await expect(page.locator('#admin-sidebar')).toHaveCount(1)

  const menu = page.locator('button[aria-controls="admin-sidebar"]:visible')
  const sidebar = page.locator('#admin-sidebar:visible')
  await menu.click()
  await expect(sidebar).toBeFocused()
  await expect(sidebar).toHaveCSS('transform', 'matrix(1, 0, 0, 1, 0, 0)')
  await page.waitForTimeout(250)

  const disappearingLink = sidebar.locator('a[href="/dashboard"]')
  await expect(disappearingLink).toBeVisible()
  await disappearingLink.focus()
  await expect(disappearingLink).toBeFocused()
  await disappearingLink.evaluate(element => element.remove())
  await expect(disappearingLink).toHaveCount(0)
  await expect(page.locator('body')).toBeFocused()

  // Model React's permission-filter reconciliation: the focused node is removed and focus
  // falls to BODY before the held session response completes.
  await page.keyboard.press('Escape')
  await expect(menu).toHaveAttribute('aria-expanded', 'false')
  await expect(menu).toBeFocused()
  await expect(sidebar).toBeHidden()
  releaseSession()
})
