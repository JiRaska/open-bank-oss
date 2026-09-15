// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { LANG_STORAGE_KEY } from '../src/lib/i18n/language'

const id = 'bde18d9e-3e49-4f4e-98cc-a56646ccbc61'
const party = 'd52f0505-bb8a-4a9f-b8e0-9c9c5f765876'
const device = 'ab72c875-9e17-4491-a5ef-0bdb34946a3b'

test('operator reviews the bound target and confirms it in a contained mobile dialog', async ({ page, context, baseURL }, testInfo) => {
  await signInAsOperator(context, baseURL!)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.addInitScript(key => localStorage.setItem(key, 'en'), LANG_STORAGE_KEY)
  let decisions = 0
  await page.route(`**/api/sca/approvals/${id}`, async route => {
    const approve = route.request().method() === 'PATCH'
    if (approve) {
      decisions += 1
      expect(route.request().postDataJSON()).toEqual({ approve: true })
    }
    await route.fulfill({ json: {
      id, action: 'device.revoke', resourceId: `${party}@${device}`, status: approve ? 'APPROVED' : 'PENDING',
      makerId: 'maker', createdAt: '2026-09-14T00:00:00Z', decidedBy: approve ? 'checker' : null,
    } })
  })
  await page.goto(`/approvals/sca/${id}`)
  await expect(page.getByRole('heading', { name: /SCA operation review|Posouzení operace SCA/ })).toBeVisible()
  const approve = page.getByRole('button', { name: /^(Approve|Schválit)$/ })
  await expect(approve).toBeDisabled()
  await page.getByRole('checkbox').check()
  await approve.click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toContainText(`${party}@${device}`)
  const bounds = await dialog.boundingBox()
  expect(bounds).not.toBeNull()
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390)
  await page.screenshot({ path: testInfo.outputPath('sca-approval-mobile.png'), fullPage: true })
  await page.getByRole('button', { name: /Confirm decision|Potvrdit rozhodnutí/ }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('status')).toContainText(/Approval recorded|Schválení zaznamenáno/)
  expect(decisions).toBe(1)
})
