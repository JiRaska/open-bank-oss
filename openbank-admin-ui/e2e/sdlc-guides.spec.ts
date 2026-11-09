// SPDX-License-Identifier: Apache-2.0
import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { LANG_COOKIE } from '../src/lib/i18n/language'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await context.addCookies([{ name: LANG_COOKIE, value: 'cs', url: baseURL! }])
})

test('CI passengers board, ride and arrive; the shorter lane has its own explanation', async ({ page }, testInfo) => {
  await page.goto('/devops/sdlc/ci')
  const train = page.getByRole('region', { name: /Každý lvíček|Every lion/ })
  await expect(train).toBeVisible()
  const scene = train.getByRole('img', { name: /Lví vláček|Lion change train/ })
  await expect(scene).toHaveAccessibleDescription(/čekají na nástupišti|wait on the platform/)
  for (let i = 0; i < 2; i++) await train.getByRole('button', { name: /Další stanice|Next station/ }).click()
  await expect(scene).toHaveAccessibleDescription(/nastoupili|have boarded/)
  await scene.screenshot({ path: testInfo.outputPath('ci-lion-train.png') })
  await train.getByRole('button', { name: /Co když kontrola selže|What if a check fails/ }).click()
  await expect(train.getByRole('region', { name: /Co se právě děje|What is happening now/ })).toContainText(/dávka půlí|halves the batch/)
  await train.getByRole('button', { name: /Rychlá kolej|Fast lane/ }).click()
  await train.getByRole('button', { name: /Závěrečné ověření|Final check/ }).click()
  await expect(train).toContainText(/carrier není potřeba|No shared carrier is needed/)
  await train.getByRole('button', { name: /Další stanice|Next station/ }).click()
  await expect(scene).toHaveAccessibleDescription(/oslavují|celebrate/)
  await page.getByRole('navigation', { name: /SDLC/ }).getByRole('link', { name: /^CD/ }).click()
  await expect(page).toHaveURL(/\/devops\/sdlc\/cd$/)
})

test('CD distinguishes declared canary weights, missing measurements and data recovery', async ({ page }, testInfo) => {
  await page.goto('/devops/sdlc/cd')
  await expect(page.getByRole('heading', { level: 1 })).toContainText(/CD:/)
  await expect(page.getByText(/nikoli právě běžící cluster|not the live cluster/)).toBeVisible()
  await page.getByRole('heading', { name: /Canary:/ }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('cd-current-state.png') })
  await page.getByText(/Přesné podmínky a limity|Exact configured conditions/).click()
  await expect(page.getByRole('table')).toContainText('failureLimit')
  await expect(page.getByText(/Návrat image nevrací|Rolling back an image does not undo/)).toBeVisible()
})

for (const route of ['ci', 'cd']) {
  test(`${route} supports keyboard, narrow viewports and both themes`, async ({ page }) => {
    await page.goto(`/devops/sdlc/${route}`)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    if (route === 'ci') {
      const lane = page.getByRole('button', { name: /Přísná kolej|Strict lane/ })
      await lane.focus()
      await lane.press('Enter')
      await expect(lane).toHaveAttribute('aria-pressed', 'true')
    }
    const themeToggle = page.getByRole('button', { name: /Přepnout na .* motiv|Switch to the .* theme/ })
    for (const theme of ['light', 'dark']) {
      const pressed = theme === 'dark' ? 'true' : 'false'
      if (await themeToggle.getAttribute('aria-pressed') !== pressed) {
        await themeToggle.click()
      }
      await expect(themeToggle).toHaveAttribute('aria-pressed', pressed)
      // Contrast must sample settled colours, not the shell's theme transition.
      await page.waitForTimeout(300)
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()
      expect(results.violations).toEqual([])
    }
    await page.setViewportSize({ width: 375, height: 812 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await expect(page.getByRole('navigation', { name: /SDLC/ }).getByRole('link', { name: /^CI/ })).toBeVisible()
  })
}
