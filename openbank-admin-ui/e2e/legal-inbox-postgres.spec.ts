// SPDX-License-Identifier: Apache-2.0

import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'
import { Client } from 'pg'
import { signInWithRoles } from './helpers/auth'

const bearer = process.env.LEGAL_INBOX_E2E_BEARER
const deliveryId = process.env.LEGAL_INBOX_E2E_DELIVERY_ID
const original = process.env.LEGAL_INBOX_E2E_ORIGINAL
const receipt = process.env.LEGAL_INBOX_E2E_RECEIPT
const subject = process.env.LEGAL_INBOX_E2E_SUBJECT
const mailbox = process.env.LEGAL_INBOX_E2E_MAILBOX
const live = process.env.LEGAL_INBOX_E2E_LIVE_ISDS === 'true'
const jdbcUrl = process.env.LEGAL_INBOX_E2E_JDBC_URL
const password = process.env.LEGAL_INBOX_E2E_DB_PASSWORD
const pollArgs = process.env.LEGAL_INBOX_E2E_POLL_ARGS

test('polls ISDS, downloads the stored originals and assigns the extracted draft', async ({ page, baseURL }) => {
  test.skip(!bearer || !deliveryId || !original || !receipt || !subject || !mailbox || !baseURL || !jdbcUrl || !password, 'Requires a PostgreSQL-backed legal process service and expected originals')
  test.setTimeout(180_000)
  if (!live) {
    await test.step('Read the simulated ISDS mailbox into empty PostgreSQL', async () => {
      expect(pollArgs, 'Prepared simulator arguments').toBeTruthy()
      await promisify(execFile)('java', ['@' + pollArgs], { timeout: 30_000 })
    })
  }
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
    await expect(scanned.first()).toContainText(/Dokončeno|Complete/)
  }

  await test.step('Verify the originals digest and assignment audit in PostgreSQL', async () => {
    const target = new URL(jdbcUrl!.replace(/^jdbc:/, ''))
    const db = new Client({
      host: target.hostname, port: Number(target.port), database: target.pathname.slice(1),
      user: 'postgres', password,
    })
    await db.connect()
    try {
      const result = await db.query(
        "SELECT array_agg(a.action ORDER BY a.id) AS actions, " +
        "count(*) FILTER (WHERE a.action = 'ASSIGN' AND a.previous_team IS NULL " +
        "AND a.next_team = 'LEGAL_REVIEW_CZ' AND a.review_revision = 1)::integer AS assigned, " +
        "bool_and(r.team = 'LEGAL_REVIEW_CZ' AND r.revision = 1 AND trim(i.content_sha256) = " +
        "encode(sha256(int4send(octet_length(e.signed_message)) || e.signed_message || e.signed_delivery_info), 'hex')) AS verified " +
        "FROM legal_inbox_audit a JOIN legal_delivery_inbox i USING (receipt_id) " +
        "JOIN legal_inbox_review r USING (receipt_id) " +
        "JOIN legal_isds_evidence e ON i.evidence_id = e.evidence_id " +
        "WHERE a.actor_subject = 'synthetic-browser-operator' AND i.source_namespace = $1 AND i.delivery_id = $2",
        ['isds:' + mailbox, deliveryId],
      )
      expect(result.rows).toEqual([{
        actions: ['DOWNLOAD_MESSAGE', 'DOWNLOAD_DELIVERY', 'EXTRACT', 'READ_DRAFT', 'ASSIGN', 'READ_DRAFT', 'READ_DRAFT'],
        assigned: 1,
        verified: true,
      }])
    } finally {
      await db.end()
    }
  })
})
