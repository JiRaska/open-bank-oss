// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme } from './helpers/theme'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('OpenBank assistant dock', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme preserves review evidence and keyboard recovery`, async ({ page }) => {
      await page.route('**/api/agent/chat', async route => {
        if (route.request().method() === 'GET') {
          await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({
              default: 'mock-echo',
              models: [{ id: 'mock-echo', provider: 'local', sensitivity: 'public' }],
            }),
          })
          return
        }
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            reply: 'I found the account, but the requested action remains blocked.',
            isProposal: true,
            toolCalls: [{ tool: 'account.read', allowed: false, resultPreview: 'Denied by policy' }],
          }),
        })
      })
      await setOperatorTheme(page, theme)
      await page.goto('/settings')

      const trigger = page.getByRole('button', { name: /Open assistant|Otevřít asistenta/i })
      await trigger.click()

      const dialog = page.getByRole('dialog', { name: /OpenBank assistant panel|Panel asistenta OpenBank/i })
      await expect(dialog).toBeVisible()
      const input = page.getByRole('textbox', { name: /Message for assistant|Zpráva pro asistenta/i })
      await expect(input).toBeFocused()
      await input.fill('Show me the account and change its status')
      await page.getByRole('button', { name: /Send message|Odeslat zprávu/i }).click()

      await expect(dialog.getByText(/Requires your review before acting|Vyžaduje vaši kontrolu před provedením/i)).toBeVisible()
      await expect(dialog.getByText('I found the account, but the requested action remains blocked.')).toBeVisible()
      await expect(dialog.getByText('account.read')).toBeVisible()

      const results = await new AxeBuilder({ page })
        .include('#agent-dock-panel')
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])

      await page.keyboard.press('Escape')
      await expect(dialog).toBeHidden()
      await expect(page.getByRole('button', { name: /Open assistant|Otevřít asistenta/i })).toBeFocused()
    })
  }
})
