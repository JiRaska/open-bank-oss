// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/services/governance', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ items: [] }),
  }))
  await page.route('**/api/services/health', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ services: [] }),
  }))
})

test('keeps quick search truthful through an outage and retry', async ({ page }) => {
  let attempts = 0
  await page.route('**/api/entities/resolve?**', async route => {
    attempts += 1
    if (attempts === 1) {
      await route.fulfill({ status: 503, body: '' })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        results: [{
          type: 'party',
          id: 'party-42',
          label: 'Alena Nováková',
          sublabel: 'INDIVIDUAL · ACTIVE',
          route: '/parties/party-42',
        }],
      }),
    })
  })

  await page.goto('/dashboard')
  await page.keyboard.press('Control+K')

  const dialog = page.getByRole('dialog', { name: /Rychlé hledání|Quick search/ })
  const input = dialog.getByRole('textbox')
  await expect(input).toBeFocused()
  await input.fill('nov')

  await expect(dialog.getByRole('alert')).toContainText(/dočasně nedostupné|temporarily unavailable/i)
  await expect(dialog.getByText(/Nic nenalezeno|No results/i)).toHaveCount(0)
  await expect(input).toHaveValue('nov')

  await dialog.getByRole('button', { name: /Zkusit znovu|Try again/i }).click()
  await expect(dialog.getByRole('option', { name: /Alena Nováková/ })).toBeVisible()
  await expect(input).toHaveValue('nov')
  await expect(dialog.getByRole('listbox')).toHaveAttribute('aria-busy', 'false')

  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/parties\/party-42$/)
  expect(attempts).toBe(2)
})
