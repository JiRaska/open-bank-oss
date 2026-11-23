// SPDX-License-Identifier: Apache-2.0
import AxeBuilder from '@axe-core/playwright'
import { expect, test } from './helpers/test'
import { signInAsOperator } from './helpers/auth'
import { LANG_COOKIE } from '../src/lib/i18n/language'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await context.addCookies([{ name: LANG_COOKIE, value: 'cs', url: baseURL! }])
})

test('CI passengers board, ride and arrive; the shorter lane has its own explanation', async ({ page }, testInfo) => {
  await page.goto('/devops/sdlc/ci')
  const train = page.getByRole('region', { name: /Více kolejí|Several tracks/ })
  await expect(train).toBeVisible()
  const scene = train.getByRole('img', { name: /Lví vláček|Lion change train/ })
  const playback = train.locator('[data-play-control]')
  // The shared reduced-motion fixture pauses after hydration; toggling the SSR state races that effect.
  await expect(playback).toHaveAttribute('aria-pressed', 'false')
  await playback.click()
  await expect(playback).toHaveAttribute('aria-pressed', 'true')
  await playback.click()
  await expect(playback).toHaveAttribute('aria-pressed', 'false')
  await expect(scene).toHaveAccessibleDescription(/tří souběžných kolejí|three concurrent tracks/)
  await expect(scene.locator('[data-lane]')).toHaveCount(3)
  await expect(scene).toHaveAccessibleDescription(/čeká na ověření|awaits verification/)
  for (let i = 0; i < 2; i++) await train.getByRole('button', { name: /Další stanice|Next station/ }).click()
  await expect(scene).toHaveAccessibleDescription(/nastoupili|have boarded/)
  await train.getByRole('button', { name: /Rychlá kolej|Fast lane/ }).click()
  await expect(scene.locator('[data-lane="fast"]')).toHaveAttribute('data-step', '1')
  await train.getByRole('button', { name: /Dávkový vlak|Batch train/ }).click()
  await expect(scene).toHaveAccessibleDescription(/nastoupili|have boarded/)
  // A captured frame must include decoded passengers, not just the faster backdrop.
  await page.evaluate(async () => {
    await Promise.all(['/brand/sdlc/train-passengers.png', '/brand/sdlc/train-atlas-v3.webp', '/brand/sdlc/heritage-coach.webp', '/brand/sdlc/masaryk-station-parallel.webp', '/brand/sdlc/openbank-mark.svg'].map(async src => {
      const image = new Image()
      image.src = src
      await image.decode()
    }))
  })
  await scene.screenshot({ path: testInfo.outputPath('ci-lion-train.png') })
  await train.getByRole('button', { name: /Co když kontrola selže|What if a check fails/ }).click()
  await expect(train.getByRole('region', { name: /Co se právě děje|What is happening now/ })).toContainText(/dávka půlí|halves the batch/)
  await train.getByRole('button', { name: /Rychlá kolej|Fast lane/ }).click()
  await train.getByRole('list', { name: /Stanice cesty změny|Stations on the change journey/ }).getByRole('button', { name: /Závěrečné ověření|Final check/ }).click()
  await expect(train).toContainText(/carrier není potřeba|No shared carrier is needed/)
  await train.getByRole('button', { name: /Další stanice|Next station/ }).click()
  await expect(scene).toHaveAccessibleDescription(/oslavují|celebrate/)
  await page.getByRole('navigation', { name: /SDLC/ }).getByRole('link', { name: /^CD/ }).click()
  await expect(page).toHaveURL(/\/devops\/sdlc\/cd$/)
})

test('CI scene, lane selection and phases fit together in the viewport', async ({ page }, testInfo) => {
  await page.goto('/devops/sdlc/ci')
  const train = page.getByRole('region', { name: /Více kolejí|Several tracks/ })
  const board = train.locator('[data-train-board]')
  const playback = train.locator('[data-play-control]')
  // The shared reduced-motion fixture pauses after hydration; toggling the SSR state races that effect.
  await expect(playback).toHaveAttribute('aria-pressed', 'false')
  for (const viewport of [{ width: 1280, height: 720 }, { width: 1920, height: 1080 }, { width: 375, height: 812 }]) {
    await page.setViewportSize(viewport)
    await board.scrollIntoViewIfNeeded()
    const geometry = await board.evaluate(element => {
      const box = element.getBoundingClientRect()
      const controls = Array.from(element.querySelectorAll('button')).map(button => button.getBoundingClientRect())
      return {
        height: box.height,
        overflow: document.documentElement.scrollWidth > window.innerWidth || element.scrollWidth > element.clientWidth,
        controlsInside: controls.every(control => control.left >= box.left && control.right <= box.right + 1 && control.top >= box.top && control.bottom <= box.bottom + 1),
        minTarget: Math.min(...controls.map(control => Math.min(control.width, control.height))),
      }
    })
    // Reserve space for the real app header; shrinking the illustration must keep native controls readable.
    expect(geometry.height).toBeLessThanOrEqual(viewport.height - 96)
    expect(geometry.overflow).toBe(false)
    expect(geometry.controlsInside).toBe(true)
    expect(geometry.minTarget).toBeGreaterThanOrEqual(viewport.width < 620 ? 44 : 36)
    const fast = board.getByRole('button', { name: /Rychlá kolej|Fast lane/ })
    await fast.focus()
    await fast.press('Enter')
    await expect(fast).toHaveAttribute('aria-pressed', 'true')
    await board.getByRole('list', { name: /Stanice cesty změny|Stations on the change journey/ }).getByRole('button', { name: /Závěrečné ověření|Final check/ }).click()
    await board.getByRole('button', { name: /Dávkový vlak|Batch train/ }).click()
    await fast.click()
    await expect(board.locator('[data-lane="fast"]')).toHaveAttribute('data-step', '3')
    await board.getByText(/Jak to funguje|How does it work/).click()
    await expect(board.getByText(/Společný carrier není potřeba|No shared carrier is needed/).last()).toBeVisible()
    await board.getByText(/Jak to funguje|How does it work/).click()
    await board.screenshot({ path: testInfo.outputPath(`ci-integrated-${viewport.width}.png`) })
  }
})

test('CD distinguishes declared canary weights, missing measurements and data recovery', async ({ page }, testInfo) => {
  await page.goto('/devops/sdlc/cd')
  const main = page.getByRole('main')
  await expect(page.getByRole('heading', { level: 1 })).toContainText(/CD:/)
  await expect(main.getByText(/nikoli právě běžící cluster|not the live cluster/)).toBeVisible()
  await page.getByRole('heading', { name: /Canary:/ }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('cd-current-state.png') })
  await main.getByText(/Přesné podmínky a limity|Exact configured conditions/).click()
  await expect(page.getByRole('table')).toContainText('failureLimit')
  await expect(main.getByText(/Návrat image nevrací|Rolling back an image does not undo/)).toBeVisible()
})

for (const route of ['ci', 'cd']) {
  test(`${route} supports keyboard, narrow viewports and both themes`, async ({ page }) => {
    await page.goto(`/devops/sdlc/${route}`)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    if (route === 'ci') {
      await expect(page.locator('[data-play-control]')).toHaveAttribute('aria-pressed', 'false')
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
