// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme, type OperatorTheme } from './helpers/theme'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

for (const theme of ['light', 'dark'] as const satisfies readonly OperatorTheme[]) {
  test(`recovers the ${theme} referral catalogue without hiding programme safeguards`, async ({ page }) => {
    await setOperatorTheme(page, theme)
    await page.setViewportSize({ width: 320, height: 760 })
    let attempts = 0
    await page.route('**/api/referral-programs', route => {
      attempts += 1
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(attempts === 1
          ? { state: 'unreachable', items: [] }
          : {
              state: 'ok',
              items: [{
                id: 'spring-savers@3',
                name: 'Spring savers',
                version: 3,
                rewardAmount: 500,
                currency: 'CZK',
                qualifyingEvent: 'FIRST_SAVINGS_DEPOSIT',
                attributionWindowEndsAt: '2027-03-31T22:00:00Z',
                status: 'PUBLISHED',
                checker: 'checker.operator',
              }],
            }),
      })
    })

    await page.goto('/campaigns/referrals')
    await expect(page.getByText(/Referral-service is not responding|Referral-service neodpovídá/)).toBeVisible()
    await page.getByRole('button', { name: /Try loading again|Zkusit načíst znovu/ }).click()
    const card = page.locator('[data-referral-program="Spring savers@3"]')
    await expect(card).toBeVisible()
    await expect(card).toContainText('FIRST_SAVINGS_DEPOSIT')
    await expect(page.getByText(/Maker and checker must be different|Autor a schvalovatel musí být různí/)).toBeVisible()

    const semanticSurface = await page.evaluate(() => {
      const probe = document.createElement('div')
      probe.style.backgroundColor = 'var(--surface)'
      document.body.append(probe)
      const color = getComputedStyle(probe).backgroundColor
      probe.remove()
      return color
    })
    await expect(card).toHaveCSS('background-color', semanticSurface)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    const results = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
    expect(attempts).toBe(2)
  })
}
