import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test('keeps every system-health summary state visible at the narrow mobile boundary', async ({ page, context, baseURL }) => {
  await page.setViewportSize({ width: 320, height: 760 })
  await signInAsOperator(context, baseURL!)
  await page.goto('/system/health')

  const summary = page.getByTestId('health-summary')
  await expect(summary).toBeVisible()
  await expect(summary.getByTestId('health-summary-card')).toHaveCount(3)
  await expect(summary).toContainText('Healthy')
  await expect(summary).toContainText('Degraded')
  await expect(summary).toContainText('Unreachable')

  const cards = await summary.getByTestId('health-summary-card').evaluateAll(elements => elements.map(element => {
    const rect = element.getBoundingClientRect()
    return { left: rect.left, right: rect.right, width: rect.width }
  }))
  expect(cards.every(card => card.left >= 0 && card.right <= 320 && card.width > 0)).toBe(true)
})
