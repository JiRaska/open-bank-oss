// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test('keeps the desktop shell visually stable while permissions resolve', async ({ page, context, baseURL }) => {
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

  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/dashboard', { waitUntil: 'domcontentloaded' })
  await sessionRequested
  const nav = page.locator('#admin-sidebar nav')
  const userMenu = page.getByRole('button', { name: 'Open user menu' })
  await expect(nav).toHaveAttribute('aria-busy', 'true')
  await expect(nav).toBeHidden()
  await expect(userMenu).toBeHidden()

  releaseSession()
  await expect(nav).toHaveAttribute('aria-busy', 'false')
  await expect(nav.locator('a[href="/system/tests"]').first()).toBeVisible()
  await expect(userMenu).toBeVisible()
  await page.waitForTimeout(250)
  const shellShift = await page.evaluate(() => (
    (Reflect.get(window, '__shellShifts') as number[]).reduce((total, value) => total + value, 0)
  ))
  expect(shellShift).toBeLessThan(0.01)
})
