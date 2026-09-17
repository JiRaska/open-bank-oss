// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

for (const width of [1440, 390, 320]) test(`keeps the ${width}px shell visually stable while permissions resolve`, async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)

  let releaseSession!: () => void
  const heldSession = new Promise<void>(resolve => { releaseSession = resolve })
  let signalSessionRequested!: () => void
  const sessionRequested = new Promise<void>(resolve => { signalSessionRequested = resolve })
  await page.route('**/api/auth/session', async route => {
    signalSessionRequested()
    await heldSession
    await route.continue()
  })
  await page.addInitScript(() => {
    const shifts: number[] = []
    Object.assign(window, { __shellShifts: shifts })
    new PerformanceObserver(list => {
      for (const entry of list.getEntries()) {
        const shift = entry as PerformanceEntry & {
          hadRecentInput: boolean
          value: number
          sources?: Array<{ node?: Node }>
        }
        if (shift.hadRecentInput) continue
        if (shift.sources?.some(source => source.node instanceof Element &&
          (source.node.closest('#admin-sidebar') || source.node.closest('header')))) {
          shifts.push(shift.value)
        }
      }
    }).observe({ type: 'layout-shift', buffered: true })
  })

  await page.setViewportSize({ width, height: 900 })
  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  await sessionRequested
  const shell = page.locator('.ob-app-shell:visible')
  await expect(shell).toHaveCount(1)
  const nav = shell.locator('#admin-sidebar nav')
  const userMenu = page.getByRole('button', { name: 'Open user menu' })
  const search = page.getByRole('button', { name: 'Quick search (⌘K)' })
  await expect(nav).toHaveAttribute('aria-busy', 'true')
  await expect(nav).toBeHidden()
  await expect(userMenu).toBeHidden()
  const searchBefore = width <= 860 ? await search.boundingBox() : null

  releaseSession()
  await expect(shell).toHaveCount(1)
  await expect(nav).toHaveAttribute('aria-busy', 'false')
  if (width > 860) await expect(nav.locator('a[href="/system/tests"]').first()).toBeVisible()
  await expect(userMenu).toBeVisible()
  await page.waitForTimeout(250)
  if (width > 860) {
    const shellShift = await page.evaluate(() => (
      (Reflect.get(window, '__shellShifts') as number[]).reduce((total, value) => total + value, 0)
    ))
    expect(shellShift).toBeLessThan(0.01)
  } else {
    const searchAfter = await search.boundingBox()
    expect(searchBefore).not.toBeNull()
    expect(searchAfter).not.toBeNull()
    expect(Math.abs(searchAfter!.x - searchBefore!.x)).toBeLessThan(1)
    // These shortcuts are duplicated in the permission-filtered mobile drawer.
    await expect(page.locator('header a[href="/docs"]')).toBeHidden()
    await expect(page.locator('header a[href="/approvals"]')).toBeHidden()
    await expect(page.locator('header a[href="/docs/release-notes/admin-ui"]')).toBeHidden()
    await expect(nav.locator('a[href="/docs"]')).toHaveCount(1)
    await expect(nav.locator('a[href="/approvals"]')).toHaveCount(1)
    await userMenu.click()
    await expect(page.getByRole('menuitem', { name: 'Release notes' })).toBeVisible()
  }
})
