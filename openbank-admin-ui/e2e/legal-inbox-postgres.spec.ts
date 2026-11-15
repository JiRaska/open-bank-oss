// SPDX-License-Identifier: Apache-2.0

import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

const bearer = process.env.LEGAL_INBOX_E2E_BEARER
const deliveryId = process.env.LEGAL_INBOX_E2E_DELIVERY_ID
const original = process.env.LEGAL_INBOX_E2E_ORIGINAL
const receipt = process.env.LEGAL_INBOX_E2E_RECEIPT
const subject = process.env.LEGAL_INBOX_E2E_SUBJECT
const mailbox = process.env.LEGAL_INBOX_E2E_MAILBOX
const live = process.env.LEGAL_INBOX_E2E_LIVE_ISDS === 'true'

test('opens a stored ISDS original and assigns its extracted draft through the live BFF', async ({ page, baseURL }) => {
  test.skip(!bearer || !deliveryId || !original || !receipt || !subject || !mailbox || !baseURL, 'Requires a PostgreSQL-backed legal process service and expected originals')
  test.setTimeout(120_000)
  await signInWithRoles(page.context(), baseURL!, ['ROLE_LEGAL_INBOX_VIEW'], bearer)
  await page.goto('/legal-process/inbox')
  await page.getByRole('button', { name: deliveryId, exact: true }).click()
  const exported = page.waitForEvent('download')
  await page.getByRole('button', { name: /Stáhnout originál ZFO|Download original ZFO/ }).click()
  const download = await exported
  expect(download.suggestedFilename()).toBe('isds-message.zfo')
  expect(await readFile(await download.path())).toEqual(await readFile(original!))

  const exportedReceipt = page.waitForEvent('download')
  await page.getByRole('button', { name: /Stáhnout doručenku ZFO|Download receipt ZFO/ }).click()
  const receiptDownload = await exportedReceipt
  expect(receiptDownload.suggestedFilename()).toBe('isds-delivery.zfo')
  expect(await readFile(await receiptDownload.path())).toEqual(await readFile(receipt!))

  await page.getByRole('button', { name: /Vytěžit obálku|Extract envelope/ }).click()
  await expect(page.getByText(subject!, { exact: true })).toBeVisible()
  await page.getByRole('combobox', { name: /Přidělit týmu|Assign to team/ }).selectOption('LEGAL_REVIEW_CZ')
  await page.getByRole('button', { name: /Uložit přidělení|Save assignment/ }).click()
  await expect(page.getByText(/Přidělení uloženo|Assignment saved/)).toBeVisible()

  await page.reload()
  await page.getByRole('button', { name: deliveryId, exact: true }).click()
  await expect(page.getByRole('combobox', { name: /Přidělit týmu|Assign to team/ })).toHaveValue('LEGAL_REVIEW_CZ')
  await expect(page.getByText(subject!, { exact: true })).toBeVisible()

  await page.goto('/legal-process/connectors')
  const scanned = page.getByRole('row')
    .filter({ has: page.getByRole('cell', { name: mailbox, exact: true }) })
    .filter({ hasText: /Seznam uložen|Discovery saved/ })
  await expect(scanned.first()).toBeVisible()
  if (!live) {
    await expect(scanned.first()).toContainText(/Zprávy čekají na import|Messages pending import/)
    await expect(page.getByText('another-fixture', { exact: true })).toHaveCount(0)
  }
})
