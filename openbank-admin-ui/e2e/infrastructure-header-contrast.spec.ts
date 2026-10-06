// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/infra/status', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ postgres: { id: 'postgres', status: 'UP', latencyMs: 8, checkedAt: '2026-09-09T08:00:00Z' } }),
  }))
  await page.route('**/api/infra/lifecycle', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ components: [{
      id: 'postgres',
      running: { version: '16.0', source: 'test' },
      lifecycle: { available: false, product: null, reason: 'not measured' },
      upgrade: { patchAvailable: true, majorAvailable: false, target: '16.1', releaseNotesUrl: null },
      cve: { scanned: false, critical: 0, high: 0, medium: 0, low: 0, total: 0, top: [] },
      urgency: 'patch-available',
    }] }),
  }))
})

for (const theme of ['light', 'dark'] as const) {
  test(`infrastructure header status remains readable in ${theme} theme`, async ({ page }) => {
    await page.goto('/infrastructure')
    if (theme === 'dark') {
      await page.getByRole('button', { name: /Switch to the dark theme|Přepnout na tmavý motiv/ }).click()
      await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark')
    }

    const header = page.locator('.page-header')
    const summaries = [header.getByText(/\d+\/\d+ UP/), header.getByText(/upgradable|k aktualizaci/)]
    for (const summary of summaries) {
      await expect(summary).toBeVisible()
      // Axe cannot always compute contrast against a CSS gradient. Check the rendered text
      // colour against both opaque gradient endpoints; the weaker pair is the assertion.
      const minimumRatio = await summary.evaluate(node => {
        const rgb = (value: string) => [...value.matchAll(/\d+/g)].slice(0, 3).map(match => Number(match[0]))
        const luminance = (channels: number[]) => channels
          .map(channel => channel / 255)
          .map(channel => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
          .reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0)
        const foreground = luminance(rgb(getComputedStyle(node).color))
        const background = getComputedStyle(node.closest('.page-header')!).backgroundImage
        const endpoints = [...background.matchAll(/rgb\(\d+, \d+, \d+\)/g)]
        if (endpoints.length !== 2) throw new Error(`Expected two opaque header gradient endpoints, got ${background}`)
        return Math.min(...endpoints.map(match => {
          const surface = luminance(rgb(match[0]))
          return (Math.max(foreground, surface) + 0.05) / (Math.min(foreground, surface) + 0.05)
        }))
      })
      expect(minimumRatio).toBeGreaterThanOrEqual(4.5)
    }
    const scan = await new AxeBuilder({ page }).include('.page-header').withTags(['wcag2a', 'wcag2aa']).analyze()
    expect(scan.violations.filter(v => v.id === 'color-contrast')).toEqual([])
  })
}
