// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

function contrast(foreground: string, background: string): number {
  const luminance = (rgb: string) => {
    const channels = rgb.match(/[\d.]+/g)!.slice(0, 3).map(Number).map(value => {
      const channel = value / 255
      return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
    })
    return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
  }
  const a = luminance(foreground)
  const b = luminance(background)
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
}

const states = [
  { name: 'unknown and not exported', flywayDrift: 'unknown', evidenceExported: false },
  { name: 'no drift and exported', flywayDrift: false, evidenceExported: true },
  { name: 'drift detected', flywayDrift: true, evidenceExported: false },
] as const

for (const state of states) {
  test(`Service Map governance text meets AA contrast in both themes when ${state.name}`, async ({ page, context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
    await page.route('**/api/services/health', route => route.fulfill({
      contentType: 'application/json', body: JSON.stringify({ services: [{ port: 8100, status: 'UP' }] }),
    }))
    await page.route('**/api/services/governance', route => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        available: true,
        byService: {
          'account-service': {
            serviceName: 'account-service', dataDomain: 'core', primaryDatastore: 'PostgreSQL',
            databaseName: 'accounts', dataLineageRole: 'source', flywayDeclaredVersion: '1',
            flywayCurrentVersion: null, flywayDrift: state.flywayDrift,
            evidenceExported: state.evidenceExported,
          },
        },
      }),
    }))
    await page.route('**/api/catalog/graph', route => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ available: true, nodes: [], edges: [], infraNodes: [], externalNodes: [], infraEdges: [], externalEdges: [] }),
    }))

    await page.goto('/docs/service-map')
    await page.getByRole('button', { name: 'Account', exact: true }).click()
    const governance = page.getByTestId('service-map-governance')
    await expect(governance).toBeVisible()
    for (const theme of ['light', 'dark'] as const) {
      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await expect(governance).toHaveCSS('background-color', theme === 'dark' ? 'rgb(23, 32, 51)' : 'rgb(248, 250, 252)')
      const background = await governance.evaluate(element => getComputedStyle(element).backgroundColor)
      for (const name of ['service-map-drift', 'service-map-evidence-exported']) {
        const value = governance.getByTestId(name)
        await expect(value).toBeVisible()
        const foreground = await value.evaluate(element => getComputedStyle(element).color)
        expect(contrast(foreground, background), `${name} in ${state.name} (${theme})`).toBeGreaterThanOrEqual(4.5)
      }
      const documentation = page.getByRole('link', { name: 'Open service documentation' })
      const linkColors = await documentation.evaluate(element => {
        const style = getComputedStyle(element)
        return { foreground: style.color, background: style.backgroundColor }
      })
      expect(contrast(linkColors.foreground, linkColors.background), `service documentation link (${theme})`).toBeGreaterThanOrEqual(4.5)
      const scan = await new AxeBuilder({ page })
        .include('[data-testid="service-map-governance"]')
        .withRules(['color-contrast'])
        .analyze()
      expect(scan.violations).toEqual([])
    }
  })
}

test('selected service-group filter keeps AA contrast in both themes', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.goto('/docs/service-map')
  const selected = page.getByRole('group', { name: 'Service group filters' }).getByRole('button', { name: 'All' })
  await expect(selected).toHaveAttribute('aria-pressed', 'true')
  await page.addStyleTag({ content: '*, *::before, *::after { transition: none !important; }' })

  for (const theme of ['light', 'dark'] as const) {
    await page.evaluate(selectedTheme => {
      document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
      document.documentElement.dataset.theme = selectedTheme
    }, theme)
    await expect.poll(() => page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--surface-4').trim(),
    )).toBe(theme === 'dark' ? '#334155' : '#e2e8f0')
    const colors = await selected.evaluate(element => {
      const style = getComputedStyle(element)
      return { foreground: style.color, background: style.backgroundColor }
    })
    expect(contrast(colors.foreground, colors.background), `selected filter in ${theme}`).toBeGreaterThanOrEqual(4.5)
  }
})
