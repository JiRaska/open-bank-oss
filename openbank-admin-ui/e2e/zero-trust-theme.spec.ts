// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme } from './helpers/theme'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

for (const theme of ['light', 'dark'] as const) {
  test(`Zero-Trust map explains the deployed posture accessibly (${theme})`, async ({ page }) => {
    const duplicateKeyErrors: string[] = []
    page.on('console', message => {
      if (message.type() === 'error' && message.text().includes('same key')) duplicateKeyErrors.push(message.text())
    })
    await setOperatorTheme(page, theme)
    await page.goto('/docs/zero-trust')
    await expect(page.getByRole('heading', { level: 1, name: /Zero-Trust Security Map|Zero-Trust bezpečnostní mapa/i })).toBeVisible()
    await expect(page.getByText(/Network segmentation|Síťová segmentace/i)).toBeVisible()
    await expect(page.getByText(/Identity authentication|Ověření identity/i)).toBeVisible()
    await expect(page.getByText(/What happens to an unauthorized call|Co se stane s neautorizovaným voláním/i)).toBeVisible()

    const scan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(scan.violations).toEqual([])
    expect(duplicateKeyErrors).toEqual([])
  })
}
