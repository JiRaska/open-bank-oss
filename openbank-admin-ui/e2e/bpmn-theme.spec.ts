// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { waitForSettledShell } from './helpers/shell'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.emulateMedia({ reducedMotion: 'reduce' })
})

for (const theme of ['light', 'dark'] as const) {
  test(`/docs/bpmn keeps process education accessible in ${theme} mode`, async ({ page }) => {
    await page.goto('/docs/bpmn')
    await waitForSettledShell(page)
    if (theme === 'dark') {
      await page.getByRole('button', { name: 'Switch to the dark theme' }).click()
      await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark')
    }

    await expect(page.getByRole('heading', { level: 1, name: /Business Process Diagrams/ })).toBeVisible()
    const selector = page.getByRole('group', { name: /Business process selector|Výběr obchodního procesu/ })
    await expect(selector).toBeVisible()
    expect(await selector.getByRole('button').count()).toBeGreaterThan(1)
    await expect(page.locator('#main-content svg').first()).toBeVisible()
    await expect(page.getByRole('heading', { name: /API & Service Coverage|Pokrytí API a služeb/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /Check status|Ověřit stav/ })).toBeEnabled()
    const scan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(scan.violations, scan.violations.map(violation =>
      `${violation.id}: ${violation.nodes.map(node => node.target.join(' ')).join(', ')}`,
    ).join('\n')).toEqual([])
  })
}
