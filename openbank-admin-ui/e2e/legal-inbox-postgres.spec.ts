// SPDX-License-Identifier: Apache-2.0

import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

const bearer = process.env.LEGAL_INBOX_E2E_BEARER
const deliveryId = process.env.LEGAL_INBOX_E2E_DELIVERY_ID
const original = process.env.LEGAL_INBOX_E2E_ORIGINAL

test('opens a stored ISDS original and assigns its extracted draft through the live BFF', async ({ page, baseURL }) => {
  test.skip(!bearer || !deliveryId || !original || !baseURL, 'Requires a seeded PostgreSQL-backed legal process service')
  test.setTimeout(120_000)
  await signInWithRoles(page.context(), baseURL!, ['ROLE_LEGAL_INBOX_VIEW'], bearer)
  await page.goto('/legal-process/inbox')
  await page.getByRole('button', { name: deliveryId, exact: true }).click()
  const exported = page.waitForEvent('download')
  await page.getByRole('button', { name: /Stáhnout originál ZFO|Download original ZFO/ }).click()
  const download = await exported
  expect(download.suggestedFilename()).toBe('isds-message.zfo')
  expect(await readFile(await download.path())).toEqual(await readFile(original!))

  await page.getByRole('button', { name: /Vytěžit obálku|Extract envelope/ }).click()
  await expect(page.getByText('Synthetic inbox subject', { exact: true })).toBeVisible()
  await page.getByRole('combobox', { name: /Přidělit týmu|Assign to team/ }).selectOption('LEGAL_REVIEW_CZ')
  await page.getByRole('button', { name: /Uložit přidělení|Save assignment/ }).click()
  await expect(page.getByText(/Přidělení uloženo|Assignment saved/)).toBeVisible()

  await page.reload()
  await page.getByRole('button', { name: deliveryId, exact: true }).click()
  await expect(page.getByRole('combobox', { name: /Přidělit týmu|Assign to team/ })).toHaveValue('LEGAL_REVIEW_CZ')
  await expect(page.getByText('Synthetic inbox subject', { exact: true })).toBeVisible()
})
