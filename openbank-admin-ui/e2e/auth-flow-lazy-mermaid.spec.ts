// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('loads the Mermaid renderer only when the operator opens the token lens', async ({ page }) => {
  await page.goto('/docs/auth-flow')

  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.getByTestId('mermaid-diagram')).toHaveCount(0)

  await page.getByRole('button', { name: /② Tokeny|② Tokens/ }).click()

  const diagram = page.getByTestId('mermaid-diagram')
  await expect(diagram).toBeVisible()
  await expect(diagram.locator('svg')).toBeVisible()
  await expect(diagram.locator('script')).toHaveCount(0)
})
