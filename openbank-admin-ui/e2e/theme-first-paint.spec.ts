// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme } from './helpers/theme'

test('restores dark theme before body paint under the nonce CSP', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await setOperatorTheme(page, 'dark')
  await page.addInitScript(() => {
    Object.assign(window, { __openbankCspViolations: [] as string[] })
    document.addEventListener('securitypolicyviolation', event => {
      ;(window as typeof window & { __openbankCspViolations: string[] }).__openbankCspViolations.push(`${event.violatedDirective}:${event.blockedURI}`)
    })
  })

  const response = await page.goto('/dashboard')
  const html = await response!.text()
  const bootstrapIndex = html.indexOf("localStorage.getItem('ob-admin-theme')")
  const bodyIndex = html.indexOf('<body')

  expect(bootstrapIndex).toBeGreaterThan(0)
  expect(bootstrapIndex).toBeLessThan(bodyIndex)
  expect(html.slice(0, bodyIndex)).toMatch(/<script nonce="[^"]+"/u)
  await expect(page.locator('html')).toHaveClass(/\bdark\b/)
  expect(await page.evaluate(() => (window as typeof window & { __openbankCspViolations: string[] }).__openbankCspViolations)).toEqual([])
})
