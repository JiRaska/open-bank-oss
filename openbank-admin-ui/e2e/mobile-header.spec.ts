import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme } from './helpers/theme'

for (const width of [320, 360, 390]) {
  test(`keeps the fully hydrated admin header operable at ${width}px`, async ({ page, context, baseURL }) => {
    await page.setViewportSize({ width, height: 760 })
    await signInAsOperator(context, baseURL!)
    await setOperatorTheme(page, 'dark')
    await page.goto('/dashboard')
    await expect(page.getByRole('link', { name: 'Help and documentation' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Approvals' })).toBeVisible()

    const result = await page.locator('header').evaluate(element => {
      const controls = Array.from(element.querySelectorAll<HTMLElement>('button, a'))
        .map(control => control.getBoundingClientRect())
        .filter(rect => rect.width > 0 && rect.height > 0)
        .map(rect => ({ left: rect.left, right: rect.right, width: rect.width }))
      return {
        viewport: document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
        controls,
      }
    })

    expect(result.scrollWidth).toBe(result.viewport)
    expect(result.controls.every(control => control.left >= 0 && control.right <= result.viewport && control.width >= 32)).toBe(true)

    await page.getByRole('button', { name: 'Open user menu' }).click()
    const menu = await page.getByRole('menu', { name: 'User menu' }).boundingBox()
    expect(menu).not.toBeNull()
    expect(menu!.x).toBeGreaterThanOrEqual(0)
    expect(menu!.x + menu!.width).toBeLessThanOrEqual(width)
  })
}

test('keeps a content header and its actions inside a 320px operator viewport', async ({ page, context, baseURL }) => {
  await page.setViewportSize({ width: 320, height: 760 })
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/infra/status', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ components: {} }),
  }))
  await page.route('**/api/infra/kafka-topics', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ topics: [], clusterName: 'sandbox' }),
  }))
  await page.route('**/api/infra/lifecycle', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ components: [] }),
  }))

  await page.goto('/infrastructure')
  const contentHeader = page.locator('.page-header')
  const refresh = contentHeader.getByRole('button', { name: 'Refresh infrastructure status' })
  await expect(contentHeader).toBeVisible()
  await expect(refresh).toBeVisible()

  const layout = await contentHeader.evaluate(element => {
    const viewport = document.documentElement.clientWidth
    const bounds = element.getBoundingClientRect()
    const actions = element.querySelector<HTMLElement>('.page-header__actions')?.getBoundingClientRect()
    return {
      viewport,
      documentWidth: document.documentElement.scrollWidth,
      headerLeft: bounds.left,
      headerRight: bounds.right,
      actionsLeft: actions?.left ?? -1,
      actionsRight: actions?.right ?? viewport + 1,
    }
  })

  expect(layout.documentWidth).toBe(layout.viewport)
  expect(layout.headerLeft).toBeGreaterThanOrEqual(0)
  expect(layout.headerRight).toBeLessThanOrEqual(layout.viewport)
  expect(layout.actionsLeft).toBeGreaterThanOrEqual(layout.headerLeft)
  expect(layout.actionsRight).toBeLessThanOrEqual(layout.headerRight)
})
